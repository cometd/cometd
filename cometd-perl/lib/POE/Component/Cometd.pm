package POE::Component::Cometd;

use strict;
use warnings;

our $VERSION = '0.01';

use Carp;
use BSD::Resource;
use Cometd::Transport;

use overload '""' => \&as_string;

use POE qw(
    Wheel::SocketFactory
    Driver::SysRW
    Wheel::ReadWrite
    Filter::Line
    Component::Cometd::Connection
);

our @base_states = qw(
    _default
    register
    unregister
    notify
    signals
    send
);


sub spawn {
    my $package = shift;
    croak "Do not call the spawn method in $package";
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

    $opts{base_states} = \@base_states;

    if ($opts{MaxConnections}) {
        my $ret = setrlimit( RLIMIT_NOFILE, $opts{MaxConnections}, $opts{MaxConnections} );
        unless ( defined $ret && $ret ) {
            if ( $> == 0 ) {
                warn "Unable to set max connections limit";
            } else {
                warn "Need to be root to increase max connections";
            }
        }
    }

    my $trans = $opts{TransportPlugin} || Cometd::Transport->new();
    if ($opts{Transports}) {
        # TODO convert this to an array
        foreach my $t ( @{ $opts{Transports} } ) {
            $trans->add_transport(
                $t->{plugin},
                $t->{priority} || 0
            );
        }
    }

    bless( { 
        opts => \%opts, 
        heaps => {},
        connections => 0,
        transport => $trans,
    }, $package );
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
    if ( $c ) {
        $c->put( $_[ARG1] );
    }
}

sub add_heap {
    my $self = shift;
    my $heap = shift;
    
    $self->{heaps}->{ "$heap" } = $heap;
    undef;
}

sub create_event {
    my ( $self, $heap, $event ) = @_;
    "$heap|$event";
}

sub _log {
    my ( $self, %o ) = @_;
    if ( $o{v} <= $self->{opts}->{LogLevel} ) {
        my $sender = ( defined $self->{heap} && defined $self->{heap}->{peer_ip} )
            ? $self->{heap}->{peer_ip} : "?";
        my $type = ( defined $o{type} ) ? $o{type} : 'M';
#        my $caller = (caller(1))[3] || '????';
#        my $pk = __PACKAGE__.'::';
#        $caller =~ s/$pk//;
        my $caller = '';
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
    my ( $self, $cmd, $args ) = @_[OBJECT, ARG0, ARG1];

    if ( $cmd !~ /^_/ && $cmd =~ m/^([^\|]+)\|(.*)/ ) {
        
        return unless ($1 && $2);

#        $self->_log(v => 5, msg => "dispatching $2 for $1");

        my $sub = $self."::$2";
        
        unless ( defined( &$sub ) ) {
            $self->_log(v => 1, msg => "subroutine $sub does not exist");
            #warn "subroutine $sub does not exist";
            return 0;
        }
        
        if ( $self->{heaps}->{ $1 } ) {
            $self->{heap} = $self->{heaps}->{ $1 };
        
            splice( @_, ARG0, $#_, @$args );
            # will this leak?
            splice( @_, HEAP, 1, $self->{heap} );
       
            no strict 'refs';
            goto &$sub;

            $self->{heap} = undef;
        } else {
            warn "NO HEAP FOR EVENT $2 WITH REF $1";
        }
    }
    
    return 0;
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

