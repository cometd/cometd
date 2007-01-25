package POE::Component::Cometd;

use strict;
use warnings;

our $VERSION = '0.01';

use Carp;
use BSD::Resource;
use Cometd::Transport;
use Scalar::Util qw( weaken );

use overload '""' => sub { shift->as_string(); };

# weak list of all cometd components
our @COMPONENTS;

BEGIN {
#    eval "use POE::Loop::Epoll"; # use Event instead?
    if ( $@ ) {
        # XXX
        #warn "Epoll not found, using default";
    }
}

use POE qw(
    Wheel::SocketFactory
    Driver::SysRW
    Wheel::ReadWrite
    Filter::Line
    Component::Cometd::Connection
);
use Cometd::Session;

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
    my %opts = @_;
    my $s_alias = $opts{Alias};
    $s_alias = 'cometd' unless defined( $s_alias ) and length( $s_alias );
    $opts{Alias} = $s_alias;
    $opts{ListenPort} ||= 6000;
    $opts{TimeOut} = defined $opts{TimeOut} ? $opts{TimeOut} : 30;
    $opts{ListenAddress} ||= '0.0.0.0';
    $opts{LogLevel} = 0
        unless ( $opts{LogLevel} );

    my $self = bless( {
        name => $opts{Name},
        opts => \%opts, 
        heaps => {},
        connections => 0,
    }, $class );

    if ($opts{MaxConnections}) {
        my $ret = setrlimit( RLIMIT_NOFILE, $opts{MaxConnections}, $opts{MaxConnections} );
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
    my $self = $_[OBJECT];

    $self->{session_id} = $_[SESSION]->ID();

    if ($self->{opts}->{Transports}) {
        my $trans = $self->{opts}->{TransportPlugin} || 'Cometd::Transport';

        eval "use $trans";
    
        $trans = $self->{transport} = $trans->new( parent_id => $self->{session_id} );
     
        foreach my $t ( @{ $self->{opts}->{Transports} } ) {
            $trans->add_transport(
                $self->{session_id},
                $t->{plugin},
                $t->{priority} || 0
            );
        }
    }
    
    # XXX check if we are a client?
    if (my $ch = delete $self->{opts}->{SubManager}) {
        # keeps a weak ref
        $ch->set_comp( $self )
            if ( $ch->can( "set_comp" ) );
        $self->{chman} = $ch;
    }


    $_[KERNEL]->yield('_startup');
}

sub _default {
    my ( $self, $cmd ) = @_[OBJECT, ARG0];
    return if ( $cmd =~ m/^_/ );
    $self->_log(v => 1, msg => "_default called, no handler for $cmd");
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


sub new_connection {
    my $self = shift;
   
    my $con = POE::Component::Cometd::Connection->new(
        parent_id => $self->{session_id},
        @_
    );

    $self->{heaps}->{ $con->ID } = $con;

    $self->{connections}++;
    
    return $con;
}


sub _log {
    my ( $self, %o );
    if (ref $_[KERNEL]) {
        ($self, %o ) = @_[ OBJECT, ARG0 .. $#_ ];
#        $o{l}++;
    } else {
        ($self, %o ) = @_;
    }
    return unless ( $o{v} <= $self->{opts}->{LogLevel} );
    my $sender = ( defined $self->{heap} && defined $self->{heap}->{addr} )
        ? $self->{heap}->{addr} : "?";
    my $l = $o{l} ? $o{l}+1 : 1;
    my $caller = (caller($l))[3] || '?';
    $caller =~ s/^POE::Component/PoCo/o;
    print STDERR '['.localtime()."][$self->{connections}][$caller][$sender] $o{msg}\n";
}

sub cleanup_connection {
    my ( $self, $con ) = @_;

    return unless( $con );
    
    my $wheel = $con->{wheel};
    if ( $wheel ) {
        $wheel->shutdown_input();
        $wheel->shutdown_output();
    }
    
    $poe_kernel->alarm_remove( $con->{time_out} )
        if ( $con->{time_out} );

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
        $self->cleanup_connection( $con );
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
1;

