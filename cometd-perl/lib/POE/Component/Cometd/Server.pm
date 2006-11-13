package POE::Component::Cometd::Server;

use strict;
use warnings;

use POE qw( Component::Cometd );
use base qw( POE::Component::Cometd );
use Errno qw( EADDRINUSE );
use Socket;
use overload '""' => \&as_string;

sub spawn {
    my $self = shift->SUPER::new( @_ );
   
    POE::Session->create(
#       options => { trace => 1 },
       object_states => [
            $self => [ @{$self->{opts}->{base_states}}, qw(
                _start
                _stop
                
                local_accept
                local_receive
                local_flushed
                local_error
                local_timeout
            )]
        ],
    );

    return $self;
}

sub as_string {
    __PACKAGE__;
}

sub _start {
    my ( $kernel, $session, $self ) = @_[KERNEL, SESSION, OBJECT];

    $session->option( @{$self->{opts}->{ServerSessionOptions}} )
        if ( $self->{opts}->{ServerSessionOptions} );
    $kernel->alias_set( $self->{opts}->{ServerAlias} )
        if ( $self->{opts}->{ServerAlias} );

    $kernel->sig( INT => 'signals' );

    $self->{opts}{Name} ||= "Server";

    # create a socket factory
    $self->{wheel} = POE::Wheel::SocketFactory->new(
        BindPort       => $self->{opts}->{ListenPort},
        BindAddress    => $self->{opts}->{ListenAddress},
        Reuse          => 'yes',
        SuccessEvent   => 'local_accept',
        FailureEvent   => 'local_error',
        ListenQueue    => $self->{opts}->{ListenQueue} || 10000,
    );

    $self->_log(v => 2, msg => "Listening to port $self->{opts}->{ListenPort} on $self->{opts}->{ListenAddress}");

}

sub _stop {
    my $self = shift;
    $self->_log(v => 2, msg => $self->{opts}->{Name}." stopped.");
}

# Accept a new connection

sub local_accept {
    my ( $kernel, $self, $socket, $peer_addr, $peer_port ) =
        @_[KERNEL, OBJECT, ARG0, ARG1, ARG2];

    $peer_addr = inet_ntoa( $peer_addr );
    my ($port, $ip) = ( sockaddr_in( getsockname( $socket ) ) );
    $ip = inet_ntoa( $ip );

    my $heap = POE::Component::Cometd::Connection->new(
        local_ip => $ip,
        local_port => $port,
        peer_ip => $peer_addr,
        peer_port => $peer_port,
        addr => "$peer_addr:$peer_port",
    );
    
    # XXX could do accept check ( plugin )
    #$self->{transport}->process_plugins( [ 'local_accept', $self, $heap, $socket ] );
    # XXX then move this to an accept method/event the plugin can call
    
    $self->add_heap( $heap );
    
    $self->_log(v => 4, msg => $self->{opts}->{Name}." received connection on $ip:$port from $peer_addr:$peer_port");
    
    $heap->{wheel} = POE::Wheel::ReadWrite->new(
        Handle          => $socket,
        Driver          => POE::Driver::SysRW->new( BlockSize => 4096 ), 
        Filter          => POE::Filter::Stackable->new(
            Filters => [
                POE::Filter::Stream->new(),
            ]
        ),
        InputEvent      => $self->create_event( $heap, 'local_receive' ),
        ErrorEvent      => $self->create_event( $heap, 'local_error' ),
        FlushedEvent    => $self->create_event( $heap, 'local_flushed' ),
    );

    if ( $self->{opts}->{TimeOut} ) {
        $heap->{time_out} = $kernel->delay_set(
            $self->create_event( $heap, 'local_timeout' )
                => $self->{opts}->{TimeOut}
        );
        $self->_log(v => 4, msg => "Timeout set: id ".$heap->{time_out});
    }
    
    $self->{connections}++;
    
    $self->{transport}->process_plugins( [ 'local_connected', $self, $heap, $socket ] );
    
    return;
}


sub local_receive {
    my ( $self, $heap ) = @_[OBJECT, HEAP];
#    $self->_log(v => 4, msg => "Receive $_[ARG0]");
    $_[KERNEL]->alarm_remove( $heap->{time_out} )
        if ( $heap->{time_out} );
    
    $self->{transport}->process_plugins( [ 'local_receive', $self, $heap, $_[ARG0] ] );
    
    return;
}

sub local_flushed {
    my ( $self, $heap ) = @_[OBJECT, HEAP];
#    $self->_log(v => 2, msg => "Flushed");

    if ( $heap && $heap->close_on_flush
        && $heap->wheel && not $heap->wheel->get_driver_out_octets() ) {
        $self->cleanup_connection( $heap );
    }
    
    return;
}

sub local_error {
    my ( $kernel, $self, $heap, $operation, $errnum, $errstr ) = 
        @_[KERNEL, OBJECT, HEAP, ARG0, ARG1, ARG2];
    
    $heap->{dis_reason} = "$operation error - $errnum: $errstr";
    
    # TODO use constant
    if ( $errnum == 0 ) {
        # normal disconnect
        $self->{transport}->process_plugins( [ 'local_disconnect', $self, $heap ] );
        $self->_log(v => 1, msg => $self->{opts}->{Name}." - client disconnected : $heap->{addr}");
        return;
    } else {
        $self->_log(v => 1, msg => $self->{opts}->{Name}." encountered $operation error $errnum: $errstr");
    }
    
    if ( $errnum == EADDRINUSE ) {
            
    }
    
    $self->cleanup_connection( $heap );
    
    return;
}

sub local_timeout {
    my ( $self, $heap ) = @_[OBJECT, HEAP];
    $self->_log(v => 3, msg => "Timeout");
    
    $self->cleanup_connection( $heap );
    
    return;
}

1;
