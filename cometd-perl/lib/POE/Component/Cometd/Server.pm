package POE::Component::Cometd::Server;

use strict;
use warnings;

use POE qw( Component::Cometd );

use base qw( POE::Component::Cometd );

use Socket;
use overload '""' => \&as_string;

sub spawn {
    my $self = shift->SUPER::new( @_ );
   
    POE::Session->create(
#       options => { trace=>1 },
       heap => $self,
       object_states => [
            $self => [qw(
                _start
                _default
                _stop
                register
                unregister
                notify
                signals
                send

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

    $session->option( @{$self->{opts}{ServerSessionOptions}} )
        if ( $self->{opts}{ServerSessionOptions} );
    $kernel->alias_set( $self->{opts}{ServerAlias} )
        if ( $self->{opts}{ServerAlias} );

    $kernel->sig( INT => 'signals' );

    # create a socket factory
    $self->{wheel} = POE::Wheel::SocketFactory->new(
        BindPort       => $self->{opts}{ListenPort},
        BindAddress    => $self->{opts}{ListenAddress},
        Reuse          => 'yes',
        SuccessEvent   => 'local_accept',
        FailureEvent   => 'local_error',
        ListenQueue    => 10000,
    );

    $self->_log(v => 2, msg => "Listening to port $self->{opts}{ListenPort} on $self->{opts}{ListenAddress}");

}

sub _stop {
    $_[OBJECT]->_log(v => 2, msg => "Server stopped.");
}

sub signals {
    my ( $self, $signal_name ) = @_[OBJECT, ARG0];

    $self->_log(v => 1, msg => "Server caught SIG$signal_name");

    # to stop ctrl-c / INT
    if ( $signal_name eq 'INT' ) {
        #$_[KERNEL]->sig_handled();
    }

    return 0;
}

# Accept a new connection

sub local_accept {
    my ( $kernel, $self, $session, $socket, $peer_addr, $peer_port ) =
        @_[KERNEL, OBJECT, SESSION, ARG0, ARG1, ARG2];

    $peer_addr = inet_ntoa( $peer_addr );
    my ($port, $ip) = ( sockaddr_in( getsockname( $socket ) ) );
    $ip = inet_ntoa( $ip );

    my $cheap = POE::Component::Cometd::Connection->new(
        local_ip => $ip,
        local_port => $port,
        peer_ip => $peer_addr,
        peer_port => $peer_port,
        addr => "$peer_addr:$peer_port",
    );
    
    # XXX could do accept check ( plugin )
    #$self->{transport}->process_plugins( [ 'local_accept', $self, $cheap, $socket ] );
    # XXX then move this to an accept method/event the plugin can call
    
    $self->add_client_obj( $self->{cheap} = $cheap );
    
    $self->_log(v => 4, msg => "Server received connection on $ip:$port from $peer_addr:$peer_port");
    
    $cheap->{wheel} = POE::Wheel::ReadWrite->new(
        Handle          => $socket,
        Driver          => POE::Driver::SysRW->new( BlockSize => 4096 ), 
        Filter          => POE::Filter::Stackable->new(
            Filters => [
                POE::Filter::Stream->new(),
            ]
        ),
        InputEvent      => $self->create_event( $cheap, 'local_receive' ),
        ErrorEvent      => $self->create_event( $cheap, 'local_error' ),
        FlushedEvent    => $self->create_event( $cheap, 'local_flushed' ),
    );

    if ( $self->{opts}->{TimeOut} ) {
        $cheap->{time_out} = $kernel->delay_set(
            $self->create_event( $cheap, 'local_timeout' )
                => $self->{opts}->{TimeOut}
        );
        $self->_log(v => 4, msg => "Timeout set: id ".$cheap->{time_out});
    }
    
    $self->{cheap} = undef;

    $self->{connections}++;
    
    $self->{transport}->process_plugins( [ 'local_connected', $self, $cheap, $socket ] );
    
    return;
}


sub local_receive {
    my $self = $_[OBJECT];
#    $self->_log(v => 4, msg => "Receive $_[ARG0]");
    #$_[KERNEL]->yield( send => "$self->{cheap}" => $_[ARG0] );
    $_[KERNEL]->alarm_remove( $self->{cheap}->{time_out} )
        if ( $self->{cheap}->{time_out} );
    
    $self->{transport}->process_plugins( [ 'local_receive', $self, $self->{cheap}, $_[ARG0] ] );
    
    return;
}

sub local_flushed {
    my $self = $_[OBJECT];
#    $_[OBJECT]->_log(v => 2, msg => "Flushed");
    
    if ( $self->{cheap} && $self->{cheap}->close_on_flush ) {
        delete $self->{cheap}->{wheel};
        $self->cleanup_connection( $self->{cheap} );
    }
    
    return;
}

sub local_error {
    my ( $kernel, $self, $session, $operation, $errnum, $errstr ) = 
        @_[KERNEL, OBJECT, SESSION, ARG0, ARG1, ARG2];
    $self->_log(v => 1, msg => "Server encountered $operation error $errnum: $errstr");
#    $kernel->call($session->ID => notify => tcp_error => { session => $session->ID, operation => $operation, error_num => $errnum, err_str => $errstr });
    
    $self->cleanup_connection( $self->{cheap} );
    
    return;
}

sub local_timeout {
    my $self = $_[OBJECT];
    $self->_log(v => 3, msg => "Timeout");
    
    delete $self->{cheap}->{wheel};

    $self->cleanup_connection( $self->{cheap} );
    
    return;
}

1;
