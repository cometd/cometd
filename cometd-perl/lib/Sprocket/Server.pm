package Sprocket::Server;

use strict;
use warnings;

use POE qw(
    Wheel::SocketFactory
    Filter::Stackable
    Filter::Stream
);
use Sprocket;
use base qw( Sprocket );
use Errno qw( EADDRINUSE );
use Socket;

sub spawn {
    my $class = shift;
   
    my $self = $class->SUPER::spawn(
        $class->SUPER::new( @_ ),
        qw(
            _startup
            _stop

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

sub _startup {
    my ( $kernel, $session, $self ) = @_[KERNEL, SESSION, OBJECT];

    $session->option( @{$self->{opts}->{server_session_options}} )
        if ( $self->{opts}->{server_session_options} );
    $kernel->alias_set( $self->{opts}->{server_alias} )
        if ( $self->{opts}->{server_alias} );

    $kernel->sig( INT => 'signals' );

    $self->{name} ||= "Server";

    # create a socket factory
    $self->{wheel} = POE::Wheel::SocketFactory->new(
        BindPort       => $self->{opts}->{listen_port},
        BindAddress    => $self->{opts}->{listen_address},
        Reuse          => 'yes',
        SuccessEvent   => 'local_accept',
        FailureEvent   => 'local_wheel_error',
        ListenQueue    => $self->{opts}->{listen_queue} || 10000,
    );

    $self->_log(v => 2, msg => "Listening to port $self->{opts}->{listen_port} on $self->{opts}->{listen_address}");
}

sub _stop {
    my $self = $_[ OBJECT ];
    $self->_log(v => 2, msg => $self->{name}." stopped.");
}

# Accept a new connection

sub local_accept {
    my ( $kernel, $self, $socket, $peer_ip, $peer_port ) =
        @_[ KERNEL, OBJECT, ARG0, ARG1, ARG2 ];

    $peer_ip = inet_ntoa( $peer_ip );
    my ( $port, $ip ) = ( sockaddr_in( getsockname( $socket ) ) );
    $ip = inet_ntoa( $ip );

    # XXX could do accept check ( plugin )
    #$self->process_plugins( [ 'local_accept', $self, $con, $socket ] );
    # XXX then move this to an accept method/event the plugin can call
    
    my $con = $self->new_connection(
        local_ip => $ip,
        local_port => $port,
        peer_ip => $peer_ip,
        # TODO resolve these?
        peer_hostname => $peer_ip,
        peer_port => $peer_port,
        peer_addr => "$peer_ip:$peer_port",
    );
    
    #$self->_log(v => 4, msg => $self->{name}." received connection on $ip:$port from $peer_ip:$peer_port");
    
    $con->wheel_readwrite(
        Handle          => $socket,
        Driver          => POE::Driver::SysRW->new( BlockSize => 2048 ), 
        Filter          => POE::Filter::Stackable->new(
            Filters => [
                POE::Filter::Stream->new(),
            ]
        ),
        InputEvent      => $con->event( 'local_receive' ),
        ErrorEvent      => $con->event( 'local_error' ),
        FlushedEvent    => $con->event( 'local_flushed' ),
    );

    if ( $self->{opts}->{time_out} ) {
        $con->{time_out} = $kernel->delay_set(
            $con->event( 'local_timeout' )
                => $self->{opts}->{time_out}
        );
        $self->_log(v => 4, msg => "Timeout set: id ".$con->{time_out});
    }
    
    $self->process_plugins( [ 'local_connected', $self, $con, $socket ] );
    
    return;
}


sub local_receive {
    my ( $self, $kernel, $con ) = @_[ OBJECT, KERNEL, HEAP ];
    
    $kernel->alarm_remove( $con->{time_out} )
        if ( $con->{time_out} );
    
    $self->process_plugins( [ 'local_receive', $self, $con, $_[ARG0] ] );
    
    return;
}

sub local_flushed {
    my ( $self, $con ) = @_[ OBJECT, HEAP ];

    $con->close()
        if ( $con->close_on_flush && not $con->get_driver_out_octets() );
    
    return;
}

sub local_wheel_error {
    my ( $self, $operation, $errnum, $errstr ) = 
        @_[ OBJECT, ARG0, ARG1, ARG2 ];
    
    $self->_log(v => 1, msg => $self->{name}." encountered $operation error $errnum: $errstr (WHEEL)");
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
#        $self->_log(v => 4, msg => $self->{name}." - client disconnected : $con->{peer_addr}");
    } else {
        $self->_log(v => 3, msg => $self->{name}." encountered $operation error $errnum: $errstr");
    }
    
    $self->process_plugins( [ 'local_disconnected', $self, $con ] );
 
    $con->close();
    
    if ( $errnum == EADDRINUSE ) {
        $self->shutdown_all();
    }
    
    return;
}

sub local_timeout {
    my ( $self, $con ) = @_[ OBJECT, HEAP ];
    $self->_log(v => 3, msg => "Timeout");
    
    $con->close();
    $con->time_out( undef );
    
    return;
}

1;
