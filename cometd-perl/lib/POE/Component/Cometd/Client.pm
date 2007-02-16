package POE::Component::Cometd::Client;

use strict;
use warnings;

use POE qw(
    Component::Cometd
    Filter::Stackable
    Filter::Stream
);
use base qw( POE::Component::Cometd );

sub spawn {
    my $class = shift;
    
    my $self = $class->SUPER::spawn(
        $class->SUPER::new( @_ ),
        qw(
            _startup
            _stop

            _conn_status

            connect_to_client
            remote_connect_success
            remote_connect_timeout
            remote_connect_error
            remote_error
            remote_receive
            remote_flush
        )
    );

    return $self;
}

sub as_string {
    __PACKAGE__;
}

sub _startup {
    my ( $kernel, $session, $self ) = @_[KERNEL, SESSION, OBJECT];

    $session->option( @{$self->{opts}->{client_session_options}} )
        if ( $self->{opts}->{client_session_options} ); 
    $kernel->alias_set( $self->{opts}->{client_alias} )
        if ( $self->{opts}->{client_alias} );
    
    $self->{name} ||= "Client";

    $kernel->sig( INT => 'signals' );
    
    # connect to our client list
    if ( $self->{opts}->{client_list} 
        && ref( $self->{opts}->{client_list} ) eq 'ARRAY' ) {
        my $d = .1;
        foreach ( @{$self->{opts}->{client_list}} ) {
            $kernel->delay_set( connect_to_client => $d => $_ );
            $d += .05;
        }
    }

#    $kernel->yield( '_conn_status' );
}

sub _stop {
    my $self = $_[OBJECT];
    $self->_log(v => 2, msg => $self->{name}." stopped.");
}

sub _conn_status {
    my $self = $_[OBJECT];
    $_[KERNEL]->delay_set( _conn_status => 30 );
    $self->_log(v => 2, msg => $self->{name}." : REMOTE connections: $self->{connections}");
}


# TODO inc backoff
sub reconnect_to_client {
    my ( $self, $con ) = @_;

    $con->connected( 0 );

    $con->sf( undef );
    $con->wheel( undef );

    if ( $self->{opts}->{connect_time_out} ) {
        $con->{timeout_id} = $poe_kernel->alarm_set(
            $con->event( 'remote_connect_timeout' )
                => time() + $self->{opts}->{connect_time_out}
        );
    }

    $con->socket_factory(
        RemoteAddress => $con->{peer_ip},
        RemotePort    => $con->{peer_port},
        SuccessEvent  => $con->event( 'remote_connect_success' ),
        FailureEvent  => $con->event( 'remote_connect_error' ),
    );
    
}

sub connect_to_client {
    my ( $kernel, $self, $addr ) = @_[KERNEL, OBJECT, ARG0];

    my ( $address, $port ) = ( $addr =~ /^([^:]+):(\d+)$/ ) or return;
    
    # TODO resolve this addr that isn't an ip, non blocking
    # PoCo DNS

    my $con = $self->new_connection(
        peer_ip => $address,
        peer_port => $port,
        addr => $addr,
    );
   
    $self->reconnect_to_client( $con );
    
    undef;
}

sub remote_connect_success {
    my ( $kernel, $self, $con, $socket ) = @_[KERNEL, OBJECT, HEAP, ARG0];
    my $addr = $con->{addr} = join( ':', @$con{qw( peer_ip peer_port )} ); 
    $self->_log(v => 3, msg => $self->{name}." connected to $addr");

    $kernel->alarm_remove( delete $con->{timeout_id} )
        if ( exists( $con->{timeout_id} ) );
    
    # XXX change this to assume wheel::readwrite in the connection object?
    $con->wheel( POE::Wheel::ReadWrite->new(
        Handle       => $socket,
        Driver       => POE::Driver::SysRW->new(),
        Filter       => POE::Filter::Stackable->new(
            Filters => [
                POE::Filter::Stream->new(),
            ]
        ),
        InputEvent   => $con->event( 'remote_receive' ),
        ErrorEvent   => $con->event( 'remote_error' ),
#        FlushedEvent => $con->event( 'remote_flush' ),
    ) );
    
    $con->sf( undef );

    $con->connected( 1 );
    
    $self->{transport}->process_plugins( [ 'remote_connected', $self, $con, $socket ] );
}

sub remote_connect_error {
    my ( $kernel, $self, $con ) = @_[KERNEL, OBJECT, HEAP];

    $self->_log(v => 2, msg => $self->{name}." : Error connecting to $con->{addr} : $_[ARG0] error $_[ARG1] ($_[ARG2])");

    $kernel->alarm_remove( delete $con->{timeout_id} )
        if ( exists( $con->{timeout_id} ) );

    $self->{transport}->process_plugins( [ 'remote_connect_error', $self, $con, @_[ ARG0 .. ARG2 ] ] );

    $self->cleanup_connection( $con );
#    $self->reconnect_to_client( $con );
}

sub remote_connect_timeout {
    my ( $kernel, $self, $con ) = @_[KERNEL, OBJECT, HEAP];
    
    $self->_log(v => 2, msg => $self->{name}." : timeout connecting to $con->{addr}");

    $self->{transport}->process_plugins( [ 'remote_connect_timeout', $self, $con ] );
#    $self->reconnect_to_client( $con );

    undef;
}

sub remote_receive {
    my $self = $_[OBJECT];
    #$self->_log(v => 4, msg => $self->{name}." got input ".$_[ARG0]);
    $self->{transport}->process_plugins( [ 'remote_receive', $self, @_[ HEAP, ARG0 ] ] );
}

sub remote_error {
    my ($self, $con) = @_[OBJECT, HEAP];
    $self->_log(v => 2, msg => $self->{name}." got error " . join( ' : ', @_[ARG1 .. ARG2] ) );
    
    if ( $_[ARG1] == 0 ) {
        $self->{transport}->process_plugins( [ 'remote_disconnected', $self, @_[ HEAP, ARG0 ] ] );
    } else {
        $self->{transport}->process_plugins( [ 'remote_error', $self, @_[ HEAP, ARG0 .. ARG2 ] ] );
    }
    # TODO reconnect in plugins
    
    $self->cleanup_connection( $con );
#    $self->reconnect_to_client( $_[HEAP] );
}

sub remote_flush {
#    $_[OBJECT]->_log(v => 2, msg => "got flush");
}

sub deliver_event {
    my ( $self, $event, $con, $to_source ) = @_;
    
    # XXX this is crap
    foreach my $con (values %{$self->{heaps}}) {
        # $con is a stringified version
        warn "$con is on $con->{addr}";
        if ( $con ) {
            if ( $to_source ) {
                warn "to source";
                # send only back to the source if requested
                next if ( $con ne "$con" );
                warn "sending only to the source";
            } else {
                # don't send back to the source
                #next if ( $con eq "$con" );
                warn "con passed in";
            }
        }
        if ( $con->connected && $con->wheel ) {
            warn "putting event $event to $con->{addr}";
            $con->send( $event );
        }
    }
}



1;
