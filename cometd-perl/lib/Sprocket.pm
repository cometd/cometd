package POE::Component::Cometd;

use strict;
use warnings;

our $VERSION = '0.01';

use Carp;
use BSD::Resource;
use Cometd qw( Connection Session );
use Scalar::Util qw( weaken blessed );

use overload '""' => sub { shift->as_string(); };

# weak list of all cometd components
our @COMPONENTS;

sub EVENT_NAME() { 0 }
sub SERVER()     { 1 }
sub CONNECTION() { 2 }

BEGIN {
#    eval "use POE::Loop::Epoll"; # use Event instead?
    if ( $@ ) {
        # XXX
        warn "Epoll not found, using default";
    }
    eval "use IO::AIO";
    if ($@) {
        eval 'sub CAN_AIO () { 0 }';
    } else {
        eval 'sub CAN_AIO () { 1 }';
    }
}

use POE qw(
    Wheel::SocketFactory
    Driver::SysRW
    Wheel::ReadWrite
);

sub import {
    my $self = shift;

    my @modules = @_;

#    push(@modules, 'Connection');

    my $package = caller();
    my @failed;

    foreach my $module (@modules) {
        my $code = "package $package; use POE::Component::Cometd::$module;";
        eval($code);
        if ($@) {
            warn $@;
            push(@failed, $module);
        }
    }

    @failed and croak "could not import qw(" . join(' ', @failed) . ")";
}

our @base_states = qw(
    _start
    _default
    register
    unregister
    notify
    signals
    _shutdown
    _log
    events_received
    events_ready
    aio_event
    exception
    process_plugins
    sig_child
    time_out_check
    cleanup
);


sub spawn {
    my ( $class, $self, @states ) = @_;
    
    Cometd::Session->create(
#       options => { trace => 1 },
        object_states => [
            $self => [ @base_states, @states ]
        ],
    );

    return $self;
}

sub as_string {
    __PACKAGE__;
}

sub new {
    my $class = shift;
    croak "$class requires an even number of parameters" if @_ % 2;
    my %opts = &adjust_params;
    my $s_alias = $opts{alias};
    $s_alias = 'cometd' unless defined( $s_alias ) and length( $s_alias );
    $opts{alias} = $s_alias;
    $opts{listen_port} ||= 6000;
    $opts{time_out} = defined $opts{time_out} ? $opts{time_out} : 30;
    $opts{listen_address} ||= '0.0.0.0';
    $opts{log_level} = 0
        unless ( $opts{log_level} );

    my $self = bless( {
        name => $opts{name},
        opts => \%opts, 
        heaps => {},
        connections => 0,
        plugins => {},
        plugin_pri => [],
        time_out => 10, # time_out checker
    }, $class );

    if ($opts{max_connections}) {
        my $ret = setrlimit( RLIMIT_NOFILE, $opts{max_connections}, $opts{max_connections} );
        unless ( defined $ret && $ret ) {
            if ( $> == 0 ) {
                #warn "Unable to set max connections limit";
                $self->_log(v => 1, msg => "Unable to set max connections limit");
            } else {
                #warn "Need to be root to increase max connections";
                $self->_log(v => 1, msg => "Need to be root to increase max connections");
            }
        }
    }

    push(@COMPONENTS, $self);
    weaken( $COMPONENTS[ -1 ] );
    
    return $self;
}

sub register {
    my ( $kernel, $self, $sender ) = @_[KERNEL, OBJECT, SENDER];
    $kernel->refcount_increment( $sender->ID, __PACKAGE__ );
    $self->{listeners}->{ $sender->ID } = 1;
    $kernel->post( $sender->ID => cometd_registered => $_[SESSION]->ID );
    return $_[SESSION]->ID();
}

sub unregister {
    my ( $kernel, $self, $sender ) = @_[KERNEL, OBJECT, SENDER];
    $kernel->refcount_decrement( $sender->ID, __PACKAGE__ );
    delete $self->{listeners}->{ $sender->ID };
}

# XXX keep this around?
sub notify {
    my ( $kernel, $self, $name, $data ) = @_[KERNEL, OBJECT, ARG0, ARG1];
    
    my $ret = 0;
    foreach ( keys %{$self->{listeners}} ) {
        my $tmp = $kernel->call( $_ => $name => $data );
        if ( defined( $tmp ) ) {
            $ret += $tmp;
        }
    }
    
    return ( $ret > 0 ) ? 1 : 0;
}

sub _start {
    my ( $self, $kernel ) = @_[OBJECT, KERNEL];

    $self->{session_id} = $_[SESSION]->ID();

    if ($self->{opts}->{plugins}) {
        foreach my $t ( @{ $self->{opts}->{plugins} } ) {
            $t = adjust_params($t);
            $self->add_plugin(
                $self->{session_id},
                $t->{plugin},
                $t->{priority} || 0
            );
        }
    }
    
    if (my $ev = delete $self->{opts}->{event_manager}) {
        eval "use $ev->{module}";
        if ($@) {
            $self->_log(v => 1, msg => "Error loading $ev->{module} : $@");
            $self->shutdown_all();
            return;
        }
        unless ( $ev->{options} && ref( $ev->{options} ) eq 'ARRAY' ) {
            $ev->{options} = [];
        }
        $self->{event_manager} = "$ev->{module}"->new(
            @{$ev->{options}},
            parent_id => $self->{session_id}
        );
    }

    if ( CAN_AIO ) {
        # XXX future use
#        open my $fh, "<&=".IO::AIO::poll_fileno or die "$!";    
#        $kernel->select_read($fh, 'aio_event');

        $self->{aio} = 1;
    } else {
        $self->{aio} = 0;
    }

    $self->{time_out_id} = $kernel->alarm_set( time_out_check => time() + $self->{time_out} )
        if ( $self->{time_out} );

#    $kernel->sig( DIE => 'exception' );

    $kernel->yield('_startup');
}

sub aio_event {
#    IO::AIO::poll_cb();
}

sub _default {
    my ( $self, $con, $cmd ) = @_[OBJECT, HEAP, ARG0];
    return if ( $cmd =~ m/^_(child|parent)/ );

    return $self->process_plugins( [ $cmd, $self, $con, @_[ ARG1 .. $#_ ] ] )
        if ( blessed( $con ) && $con->isa( 'Cometd::Connection' ) );
    
    $self->_log(v => 1, msg => "_default called, no handler for event $cmd [$con] (the connection for this event may be gone)");
}

sub signals {
    my ( $self, $signal_name ) = @_[OBJECT, ARG0];

    $self->_log(v => 1, msg => "Client caught SIG$signal_name");

    # to stop ctrl-c / INT
    if ($signal_name eq 'INT') {
        #$_[KERNEL]->sig_handled();
    }

    return 0;
}

sub sig_child {
    $_[KERNEL]->sig_handled();
}

sub new_connection {
    my $self = shift;
   
    my $con = Cometd::Connection->new(
        parent_id => $self->{session_id},
        @_
    );
    
    $con->event_manager( $self->{event_manager}->{alias} )
        if ( $self->{event_manager} );

    $self->{heaps}->{ $con->ID } = $con;

    $self->{connections}++;
    
    return $con;
}

sub _log {
    my ( $self, %o );
    if (ref $_[KERNEL]) {
        ( $self, %o ) = @_[ OBJECT, ARG0 .. $#_ ];
#        $o{l}++;
    } else {
        ( $self, %o ) = @_;
    }
    return unless ( $o{v} <= $self->{opts}->{log_level} );
    my $con = $self->{heap};
    my $sender = ( $con )
        ? $con->{addr}."(".$con->ID.")" : "?";
    my $l = $o{l} ? $o{l}+1 : 1;
    my $caller = $o{call} ? $o{call} : ( caller($l) )[3] || '?';
    $caller =~ s/^POE::Component/PoCo/o;
    print STDERR '['.localtime()."][$self->{connections}][$caller][$sender] $o{msg}\n";
}

sub cleanup {
    my ( $self, $con ) = @_[ OBJECT, ARG0 ];
    $self->cleanup_connection( $self->{heaps}->{$con} );
}

sub cleanup_connection {
    my ( $self, $con ) = @_;

    return unless( $con );
    
    my $wheel = $con->{wheel};
    if ( $wheel ) {
        $wheel->shutdown_input();
        $wheel->shutdown_output();
    }
    
    delete $con->{wheel};
    
    $self->{connections}--;
    delete $self->{heaps}->{ $con->ID };
    
    return undef;
}
      
sub shutdown_all {
    foreach my $comp (@COMPONENTS) {
        $comp->shutdown();
    }
}

sub shutdown {
    my $self = shift;
    $poe_kernel->call( $self->{session_id} => '_shutdown' );
}

sub _shutdown {
    my ( $self, $kernel ) = @_[ OBJECT, KERNEL ];
    foreach my $con ( values %{$self->{heaps}} ) {
        $con->close( 1 ); # force
#        $self->cleanup_connection( $con );
    }
    $self->{heaps} = {};
    foreach my $id ( keys %{$self->{listeners}} ) {
        $kernel->refcount_decrement( $id, __PACKAGE__ );
    }
    $kernel->sig( INT => undef );
    $kernel->alarm_remove_all();
    delete $self->{wheel};
    delete $self->{sf};
    return undef;
}

# TODO class:accessor::fast
sub name {
    shift->{name};
}

sub events_received {
    my ( $kernel, $self ) = @_[ KERNEL, OBJECT ];
    $self->process_plugins( [ 'events_received', $self, @_[ HEAP, ARG0 .. $#_ ] ] );
}

sub events_ready {
    my ( $kernel, $self ) = @_[ KERNEL, OBJECT ];
    $self->process_plugins( [ 'events_ready', $self, @_[ HEAP, ARG0 .. $#_ ] ] );
}

sub exception {
    my ($kernel, $self, $con, $sig, $error) = @_[KERNEL, OBJECT, HEAP, ARG0, ARG1];
    $self->_log(v => 1, l => 1, msg => "plugin exception handled: ($sig) : "
        .join(' | ',map { $_.':'.$error->{$_} } keys %$error ) );
    # doesn't work?
    if ( blessed( $con ) && $con->isa( 'Cometd::Connection' ) ) {
        $con->close( 1 );
#        $self->cleanup_connection ( $con );
    }
    $kernel->sig_handled();
}

sub time_out_check {
    my ($kernel, $self) = @_[KERNEL, OBJECT];

    my $time = time();
    $self->{time_out_id} = $kernel->alarm_set( time_out_check => $time + $self->{time_out} );

    foreach my $con ( values %{$self->{heaps}} ) {
        if ( my $timeout = $con->time_out() ) {
#            warn "$con timeout is $con->{time_out} ".( $con->active_time() + $timeout ). " < $time";
            if ( ( $con->active_time() + $timeout ) <  $time ) {
                $self->process_plugins( [ 'time_out', $self, $con, $time ] );
            }
        }
    }
}

sub add_plugin {
    my $self = shift;
    
    my $t = $self->{plugins};
   
    my ( $parent_id, $plugin, $pri ) = @_;
    my $name = $plugin->name();
    
    warn "WARNING : Overwriting existing plugin '$name' (You have two plugins with the same name)"
        if ( exists( $t->{ $name } ) );

    $t->{ $name } = {
        plugin => $plugin,
        priority => $pri || 0,
    };
    
    $plugin->parent_id( $parent_id );
    
    $plugin->add_plugin( $self )
        if ( $plugin->can( 'add_plugin' ) );
    
    # recalc plugin_pri
    @{ $self->{plugin_pri} } = sort {
        $t->{ $a }->{priority} <=> $t->{ $b }->{priority}
    } keys %{ $t };

    return 1;
}

sub remove_plugin {
    my $self = shift;
    my $tr = shift;
    
    # TODO delete by name or obj
    
    my $t = $self->{plugins};
    
    my $plugin = delete $t->{ $tr };
    
    $plugin->remove_plugin( $self )
        if ( $plugin->can( 'remove_plugin' ) );
    
    # recalc plugin_pri
    @{ $self->{plugin_pri} } = sort {
        $t->{ $a }->{priority} <=> $t->{ $b }->{priority}
    } keys %{ $t };
}

sub process_plugins {
    my ( $self, $args, $i ) = $_[ KERNEL ] ? @_[ OBJECT, ARG0, ARG1 ] : @_;

    return unless ( @{ $self->{plugin_pri} } );
    
    my $con = $args->[ CONNECTION ];
    $con->state( $args->[ EVENT_NAME ] );
   
    if ( my $t = $con->plugin() ) {
        return $self->{plugins}->{ $t }->{plugin}->handle_event( @$args );
    } else {
        $i ||= 0;
        if ( $#{ $self->{plugin_pri} } >= $i ) {
            return if ( $self->{plugins}->{
                $self->{plugin_pri}->[ $i ]
            }->{plugin}->handle_event( @$args ) );
        }
        $i++;
        # avoid a post
        return if ( $#{ $self->{plugin_pri} } < $i );
    }
    
    $poe_kernel->yield( process_plugins => $args => $i );
}

sub forward_plugin {
    my $self = shift;
    my $plug_name = shift;
    return 0 unless( exists( $self->{plugins}->{ $plug_name } ) );
    my $con = $_[ 1 ];
    $con->plugin( $plug_name );
    return $self->process_plugins( [ $con->state, @_ ] );
}


1;

