package POE::Component::Cometd::Server;

use strict;
use warnings;

use POE qw( Component::Cometd );
use base qw( POE::Component::Cometd );
use Errno qw( EADDRINUSE );
use Socket;

sub spawn {
    my $class = shift;
   
    my $self = $class->SUPER::spawn(
        $class->SUPER::new( @_ ),
        qw(
            _start
            _stop

            _conn_status
        
            local_accept
            local_receive
            local_flushed
            local_wheel_error
            local_error
            local_timeout
        )
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
        FailureEvent   => 'local_wheel_error',
        ListenQueue    => $self->{opts}->{ListenQueue} || 10000,
    );

    $self->_log(v => 2, msg => "Listening to port $self->{opts}->{ListenPort} on $self->{opts}->{ListenAddress}");

    $kernel->yield( '_conn_status' );
}

sub _stop {
    my $self = $_[ OBJECT ];
    $self->_log(v => 2, msg => $self->{opts}->{Name}." stopped.");
}

sub _conn_status {
    my $self = $_[ OBJECT ];
    $_[KERNEL]->delay_set( _conn_status => 10 );
    $self->_log(v => 2, msg => $self->{opts}->{Name}." : LOCAL connections: $self->{connections}");
}

# Accept a new connection

sub local_accept {
    my ( $kernel, $self, $socket, $peer_addr, $peer_port ) =
        @_[ KERNEL, OBJECT, ARG0, ARG1, ARG2 ];

    $peer_addr = inet_ntoa( $peer_addr );
    my ($port, $ip) = ( sockaddr_in( getsockname( $socket ) ) );
    $ip = inet_ntoa( $ip );

    # XXX could do accept check ( plugin )
    #$self->{transport}->process_plugins( [ 'local_accept', $self, $con, $socket ] );
    # XXX then move this to an accept method/event the plugin can call
    
    my $con = $self->new_connection(
        local_ip => $ip,
        local_port => $port,
        peer_ip => $peer_addr,
        peer_port => $peer_port,
        addr => "$peer_addr:$peer_port",
    );
    
    $self->_log(v => 4, msg => $self->{opts}->{Name}." received connection on $ip:$port from $peer_addr:$peer_port");
    
    $con->wheel( POE::Wheel::ReadWrite->new(
        Handle          => $socket,
        Driver          => POE::Driver::SysRW->new( BlockSize => 4096 ), 
        Filter          => POE::Filter::Stackable->new(
            Filters => [
                POE::Filter::Stream->new(),
            ]
        ),
        InputEvent      => $con->event( 'local_receive' ),
        ErrorEvent      => $con->event( 'local_error' ),
        FlushedEvent    => $con->event( 'local_flushed' ),
    ) );

    if ( $self->{opts}->{TimeOut} ) {
        $con->{time_out} = $kernel->delay_set(
            $con->event( 'local_timeout' )
                => $self->{opts}->{TimeOut}
        );
        $self->_log(v => 4, msg => "Timeout set: id ".$con->{time_out});
    }
    
    $self->{transport}->process_plugins( [ 'local_connected', $self, $con, $socket ] );
    
    return;
}


sub local_receive {
    my ( $self, $con ) = @_[ OBJECT, HEAP ];
#    $self->_log(v => 4, msg => "Receive $_[ARG0]");
    $_[KERNEL]->alarm_remove( $con->{time_out} )
        if ( $con->{time_out} );
    
    $self->{transport}->process_plugins( [ 'local_receive', $self, $con, $_[ARG0] ] );
    
    return;
}

sub local_flushed {
    my ( $self, $con ) = @_[ OBJECT, HEAP ];

    if ( $con->close_on_flush
        && $con->wheel && not $con->wheel->get_driver_out_octets() ) {
        $self->cleanup_connection( $con );
    }
    
    return;
}

sub local_wheel_error {
    my ( $self, $operation, $errnum, $errstr ) = 
        @_[ OBJECT, ARG0, ARG1, ARG2 ];
    
    $self->_log(v => 1, msg => $self->{opts}->{Name}." encountered $operation error $errnum: $errstr (WHEEL)");
}

sub local_error {
    my ( $kernel, $self, $con, $operation, $errnum, $errstr ) = 
        @_[ KERNEL, OBJECT, HEAP, ARG0, ARG1, ARG2 ];
    
    $con->{dis_reason} = "$operation error - $errnum: $errstr";
    
    $kernel->alarm_remove( $con->{time_out} )
        if ( $con->{time_out} );
    
    # TODO use constant
    if ( $errnum == 0 ) {
        # normal disconnect
    #    $self->{transport}->process_plugins( [ 'local_disconnected', $self, $con ] );
        $self->_log(v => 1, msg => $self->{opts}->{Name}." - client disconnected : $con->{addr}");
    } else {
        $self->_log(v => 1, msg => $self->{opts}->{Name}." encountered $operation error $errnum: $errstr");
    }
    $self->{transport}->process_plugins( [ 'local_disconnected', $self, $con ] );
    
    $self->cleanup_connection( $con );
    
    if ( $errnum == EADDRINUSE ) {
        $self->shutdown();
        # TODO more?
    }
    
    return;
}

sub local_timeout {
    my ( $self, $con ) = @_[ OBJECT, HEAP ];
    $self->_log(v => 3, msg => "Timeout");
    
    $self->cleanup_connection( $con );

    # TODO accessor
    delete $con->{time_out};
    
    return;
}

1;
