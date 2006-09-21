package POE::Component::Cometd;

use strict;
use warnings;

our $VERSION = '0.01';
our $LogLevel = 0;

use Carp;
use BSD::Resource;
use Cometd::Transport;

use overload '""' => \&as_string;

use POE qw(
    Wheel::SocketFactory
    Driver::SysRW
    Wheel::ReadWrite
    Filter::Line
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
    my $c_alias = $opts{ClieintAlias};
    $c_alias = 'cometd_client' unless defined( $c_alias ) and length( $c_alias );
    $opts{ClientAlias} = $c_alias;
    $opts{ListenPort} = $opts{ListenPort} || 6000;
    $opts{TimeOut} = defined $opts{TimeOut} ? $opts{TimeOut} : 30;
    $opts{ListenAddress} = $opts{ListenAddress} || '0.0.0.0';
    $POE::Component::Cometd::LogLevel = delete $opts{LogLevel} || 0;

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
        foreach my $t ( keys %{ $opts{Transports} } ) {
            $trans->add_transport(
                $t,
                $opts{Transports}->{ $t }->{priority},
                $opts{Transports}->{ $t }->{plugin}
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
    $kernel->post( $sender->ID => sb_registered => $_[SESSION]->ID );
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
    my $c = $_[OBJECT]->{heaps}->{ $_[ARG0] }->{con};
    if ( $c ) {
        $c->put( $_[ARG1] );
    }
}

sub add_client_obj {
    my $self = shift;
    my $cheap = shift;
    $self->{heaps}->{ "$cheap" } = $cheap;
    undef;
}

sub create_event {
    my ( $self, $cheap, $event ) = @_;
    "$cheap|$event";
}

sub _log {
    my ( $self, %o ) = @_;
    if ( $o{v} <= $POE::Component::Cometd::LogLevel ) {
        my $sender = ( defined $self->{cheap} && defined $self->{cheap}->{peer_ip} )
            ? $self->{cheap}->{peer_ip} : "?";
        my $type = ( defined $o{type} ) ? $o{type} : 'M';
#        my $caller = (caller(1))[3] || '????';
#        my $pk = __PACKAGE__.'::';
#        $caller =~ s/$pk//;
        my $caller = '';
        print STDERR '['.localtime()."][$type][$caller][$sender] $o{msg}\n";
    }
}

sub cleanup_connection {
    my ( $self, $cheap ) = @_;

    $self->{connections}-- if delete $self->{heaps}->{ "$cheap" };

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
            warn "subroutine $sub does not exist";
            return 0;
        }
        
        if ( $self->{heaps}->{ $1 } ) {
            $self->{cheap} = $self->{heaps}->{ $1 };
        
            splice( @_, ARG0, $#_, @$args );
       
            no strict 'refs';
            goto &$sub;

            $self->{cheap} = undef;
        } else {
            warn "NO HEAP FOR EVENT $2 WITH REF $1";
        }
    }
    
    return 0;
}

1;

