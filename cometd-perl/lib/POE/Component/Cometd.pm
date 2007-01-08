package POE::Component::Cometd;

use strict;
use warnings;

our $VERSION = '0.01';

use Carp;
use BSD::Resource;
use Cometd::Transport;

use overload '""' => sub { shift->as_string(); };

use POE qw(
    Wheel::SocketFactory
    Driver::SysRW
    Wheel::ReadWrite
    Filter::Line
    Component::Cometd::Connection
);
use Cometd::Session;

our @base_states = qw(
    _default
    register
    unregister
    notify
    signals
    send
);


sub spawn {
    my ( $package, $self, @states ) = @_;
    
    Cometd::Session->create(
#       options => { trace => 1 },
        heap => $self,
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
    my $package = shift;
    croak "$package requires an even number of parameters" if @_ % 2;
    my %opts = @_;
    my $s_alias = $opts{ServerAlias};
    $s_alias = 'cometd_server' unless defined( $s_alias ) and length( $s_alias );
    $opts{ServerAlias} = $s_alias;
    my $c_alias = $opts{ClientAlias};
    $c_alias = 'cometd_client' unless defined( $c_alias ) and length( $c_alias );
    $opts{ClientAlias} = $c_alias;
    $opts{ListenPort} ||= 6000;
    $opts{TimeOut} = defined $opts{TimeOut} ? $opts{TimeOut} : 30;
    $opts{ListenAddress} ||= '0.0.0.0';
    $opts{LogLevel} = 0
        unless ( $opts{LogLevel} );

    my $self = bless( { 
        opts => \%opts, 
        heaps => {},
        connections => 0,
    }, $package );

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

    my $trans = $self->{transport} = $opts{TransportPlugin} || Cometd::Transport->new();
    if ($opts{Transports}) {
        # TODO convert this to an array
        foreach my $t ( @{ $opts{Transports} } ) {
            $trans->add_transport(
                $t->{plugin},
                $t->{priority} || 0
            );
        }
    }

    return $self;
}

sub register {
    my ( $kernel, $self, $sender ) = @_[KERNEL, OBJECT, SENDER];
    $kernel->refcount_increment( $sender->ID, __PACKAGE__ );
    $self->{listeners}->{ $sender->ID } = 1;
    $kernel->post( $sender->ID => cometd_registered => $_[SESSION]->ID );
    #$self->_log(v => 2, msg => "Listening to port $self->{opts}{ListenPort} on $self->{opts}{ListenAddress}");
    return $_[SESSION]->ID();
}

sub unregister {
    my ( $kernel, $self, $sender ) = @_[KERNEL, OBJECT, SENDER];
    $kernel->refcount_decrement( $sender->ID, __PACKAGE__ );
    delete $self->{listeners}->{ $sender->ID };
}

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

sub send {
    my $c = $_[OBJECT]->{heaps}->{ $_[ARG0] }->{wheel};
    $c->put( $_[ARG1] )
        if ( $c );
}

sub add_heap {
    my $self = shift;
    my $heap = shift;
    
    $self->{heaps}->{ "$heap" } = $heap;
    undef;
}

sub create_event {
    # XXX call Cometd::Session to return this?
    "$_[1]|$_[2]"; # heap|event
}

sub _log {
    my ( $self, %o ) = @_;
    if ( $o{v} <= $self->{opts}->{LogLevel} ) {
        my $sender = ( defined $self->{heap} && defined $self->{heap}->{addr} )
            ? $self->{heap}->{addr} : "?";
        my $type = ( defined $o{type} ) ? $o{type} : 'M';
        my $caller = (caller(1))[3] || '????';
        $caller =~ s/POE::Component:://;
        print STDERR '['.localtime()."][$type][$caller][$sender] $o{msg}\n";
    }
}

sub cleanup_connection {
    my ( $self, $heap ) = @_;

    return unless( $heap );

    delete $heap->{wheel};
    
    $self->{connections}-- if delete $self->{heaps}->{ "$heap" };

    undef;
}

sub _default {
    my ( $self, $cmd ) = @_[OBJECT, ARG0];
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

1;

