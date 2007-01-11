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
            _start
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

sub _start {
    my ( $kernel, $session, $self ) = @_[KERNEL, SESSION, OBJECT];

    $session->option( @{$self->{opts}->{ClientSessionOptions}} )
        if ( $self->{opts}->{ClientSessionOptions} ); 
    $kernel->alias_set( $self->{opts}->{ClientAlias} )
        if ( $self->{opts}->{ClientAlias} );
    
    $self->{opts}{Name} ||= "Client";

    $kernel->sig( INT => 'signals' );
    
    # connect to our client list
    if ( $self->{opts}->{ClientList} 
        && ref( $self->{opts}->{ClientList} ) eq 'ARRAY' ) {
        my $d = .1;
        foreach ( @{$self->{opts}->{ClientList}} ) {
            $kernel->delay_set( connect_to_client => $d => $_ );
            $d += .05;
        }
    }

    $kernel->yield( '_conn_status' );
}

sub _stop {
    my $self = $_[OBJECT];
    $self->_log(v => 2, msg => $self->{opts}->{Name}." stopped.");
}

sub _conn_status {
    my $self = $_[OBJECT];
    $_[KERNEL]->delay_set( _conn_status => 30 );
    $self->_log(v => 2, msg => $self->{opts}->{Name}." : REMOTE connections: $self->{connections}");
}


# TODO inc backoff
sub reconnect_to_client {
    my ( $self, $con ) = @_;

    $con->connected( 0 );

    delete $con->{sf};
    delete $con->{wheel};

    if ( $self->{opts}->{ConnectTimeOut} ) {
        $con->{timeout_id} = $poe_kernel->alarm_set(
            $self->create_event( $con,'remote_connect_timeout' )
                => time() + $self->{opts}->{ConnectTimeOut}
        );
    }

    $con->{sf} = POE::Wheel::SocketFactory->new(
        RemoteAddress => $con->{peer_ip},
        RemotePort    => $con->{peer_port},
        SuccessEvent  => $self->create_event( $con,'remote_connect_success' ),
        FailureEvent  => $self->create_event( $con,'remote_connect_error' ),
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
    $self->_log(v => 3, msg => $self->{opts}->{Name}." connected to $addr");

    $kernel->alarm_remove( delete $con->{timeout_id} )
        if ( exists( $con->{timeout_id} ) );
    
    $con->wheel( POE::Wheel::ReadWrite->new(
        Handle       => $socket,
        Driver       => POE::Driver::SysRW->new(),
        Filter       => POE::Filter::Stackable->new(
            Filters => [
                POE::Filter::Stream->new(),
            ]
        ),
        InputEvent   => $self->create_event( $con,'remote_receive' ),
        ErrorEvent   => $self->create_event( $con,'remote_error' ),
#        FlushedEvent => $self->create_event( $con,'remote_flush' ),
    ) );
    delete $con->{sf};

    $con->connected( 1 );
    
    warn "connected";
    
    $self->{transport}->process_plugins( [ 'remote_connected', $self, $con, $socket ] );
}

sub remote_connect_error {
    my ( $kernel, $self, $con ) = @_[KERNEL, OBJECT, HEAP];

    $self->_log(v => 2, msg => $self->{opts}->{Name}." : Error connecting to $con->{addr} : $_[ARG0] error $_[ARG1] ($_[ARG2])");

    $kernel->alarm_remove( delete $con->{timeout_id} )
        if ( exists( $con->{timeout_id} ) );

    $self->reconnect_to_client( $con );
}

sub remote_connect_timeout {
    my ( $kernel, $self, $con ) = @_[KERNEL, OBJECT, HEAP];
    
    $self->_log(v => 2, msg => $self->{opts}->{Name}." : timeout connecting to $con->{addr}");

    $self->reconnect_to_client( $con );

    undef;
}

sub remote_receive {
    my $self = $_[OBJECT];
    $self->_log(v => 4, msg => $self->{opts}->{Name}." got input ".$_[ARG0]);
    $self->{transport}->process_plugins( [ 'remote_receive', $self, $_[HEAP], $_[ARG0] ] );
}

sub remote_error {
    my $self = $_[OBJECT];
    $self->_log(v => 2, msg => $self->{opts}->{Name}." got error " . join( ' : ', @_[ARG1 .. ARG2] ) );
    
    $self->reconnect_to_client( $_[HEAP] );
}

sub remote_flush {
#    $_[OBJECT]->_log(v => 2, msg => "got flush");

}

sub deliver_event {
    my ( $self, $event, $con, $to_source ) = @_;
    
    foreach my $con (keys %{$self->{cons}}) {
        # $con is a stringified version
        my $con = $self->{cons}->{$con};
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
