package Sprocket::Client;

use strict;
use warnings;

use POE qw(
    Filter::Stackable
    Filter::Stream
    Component::Client::DNS
);
use Sprocket;
use base qw( Sprocket );

sub spawn {
    my $class = shift;
    
    my $self = $class->SUPER::spawn(
        $class->SUPER::new( @_ ),
        qw(
            _startup
            _stop

            _conn_status

            connect
            remote_connect_success
            remote_connect_timeout
            remote_connect_error
            remote_error
            remote_receive
            remote_flush

            resolved_address
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
    if ( $self->{opts}->{client_list} ) {
        if ( ref( $self->{opts}->{client_list} ) eq 'ARRAY' ) {
            foreach ( @{$self->{opts}->{client_list}} ) {
                ( ref( $_ ) eq 'ARRAY' ) ? $self->connect( @$_ ) : $self->connect( $_ );
            }
        } else {
            warn "client list must be an array if defined at all";
        }
    }

#    $kernel->refcount_increment( $self->{session_id} => "$self" );

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

sub remote_connect_success {
    my ( $kernel, $self, $con, $socket ) = @_[KERNEL, OBJECT, HEAP, ARG0];
    my $addr = $con->{addr} = join( ':', @$con{qw( peer_ip peer_port )} ); 
    $self->_log(v => 3, msg => $self->{name}." connected to $addr");

    $kernel->alarm_remove( delete $con->{timeout_id} )
        if ( exists( $con->{timeout_id} ) );
    
    # XXX change this to assume wheel::readwrite in the connection object?
    $con->wheel( POE::Wheel::ReadWrite->new(
        Handle       => $socket,
        Driver       => POE::Driver::SysRW->new( BlockSize => 2048 ),
        Filter       => POE::Filter::Stackable->new(
            Filters => [
                POE::Filter::Stream->new(),
            ]
        ),
        InputEvent   => $con->event( 'remote_receive' ),
        ErrorEvent   => $con->event( 'remote_error' ),
        FlushedEvent => $con->event( 'remote_flush' ),
    ) );
    
    $con->sf( undef );

    $con->connected( 1 );
    
    $self->process_plugins( [ 'remote_connected', $self, $con, $socket ] );
}

sub remote_connect_error {
    my ( $kernel, $self, $con ) = @_[KERNEL, OBJECT, HEAP];

    $self->_log(v => 2, msg => $self->{name}." : Error connecting to $con->{addr} : $_[ARG0] error $_[ARG1] ($_[ARG2])");

    $kernel->alarm_remove( delete $con->{timeout_id} )
        if ( exists( $con->{timeout_id} ) );

    $self->process_plugins( [ 'remote_connect_error', $self, $con, @_[ ARG0 .. ARG2 ] ] );

    $con->close();
#    $self->reconnect( $con );
}

sub remote_connect_timeout {
    my ( $kernel, $self, $con ) = @_[KERNEL, OBJECT, HEAP];
    
    $self->_log(v => 2, msg => $self->{name}." : timeout connecting to $con->{addr}");

    $self->process_plugins( [ 'remote_connect_timeout', $self, $con ] );
#    $self->reconnect( $con );

    undef;
}

sub remote_receive {
    my $self = $_[OBJECT];
    #$self->_log(v => 4, msg => $self->{name}." got input ".$_[ARG0]);
    $self->process_plugins( [ 'remote_receive', $self, @_[ HEAP, ARG0 ] ] );
}

sub remote_error {
    my ($self, $con) = @_[OBJECT, HEAP];
    $self->_log(v => 2, msg => $self->{name}." got error " . join( ' : ', @_[ARG1 .. ARG2] ) );
    
    if ( $_[ARG1] != 0 ) {
        # XXX con disconnect reason
        #$self->process_plugins( [ 'remote_error', $self, @_[ HEAP, ARG0 .. ARG2 ] ] );
    }
    
    # TODO reconnect in plugins
    $self->process_plugins( [ 'remote_disconnected', $self, @_[ HEAP, ARG0, ARG1 ] ] );
 
    $con->close();
#    $self->reconnect( $_[HEAP] );
}

sub remote_flush {
    my ( $self, $con ) = @_[ OBJECT, HEAP ];

    # we'll get called again if there are octets out
    if ( $con->close_on_flush
        && not $con->get_driver_out_octets() ) {
        
        $con->close();
    }
    
    return;
}

sub connect {
    # must call in this in our session's context
    unless ( $_[KERNEL] && ref $_[KERNEL] ) {
        return $poe_kernel->call( shift->{session_id} => connect => @_ );
    }
    
    my ( $self, $kernel, $address, $port ) = @_[ OBJECT, KERNEL, ARG0, ARG1 ];
    
    unless( defined $port ) {
       ( $address, $port ) = ( $address =~ /^([^:]+):(\d+)$/ );
    }
    
    my $con;

    # PoCo DNS
    if ( $address !~ m/^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$/ ) {
        my $named_ses = $kernel->alias_resolve( 'named' );

        # no DNS resolver found, load one instead
        unless ( $named_ses ) {
            # could use the object here, but I don't want
            # duplicated code, so just use the session reference
            POE::Component::Client::DNS->spawn( Alias => 'named' );
            $named_ses = $kernel->alias_resolve( 'named' );
            # release ownership of this session
            #$kernel->detach_child( $named_ses );
        }

        # a new unconnected connection
        $con = $self->new_connection(
            peer_port => $port,
            peer_hostname => $address,
        );

        $kernel->call( $named_ses => 'resolve' => {
            host => $address,
            context => 1,
            event => $con->event( 'resolved_address' )
        });

        # we will connect after resolving the address
        return $con;
    } else {
        $con = $self->new_connection(
            peer_ip => $address,
            peer_port => $port,
            peer_addr => "$address:$port",
        );
    }

    return $self->reconnect( $con );
}

sub resolved_address {
    my ( $self, $con, $response ) = @_[ OBJECT, HEAP, ARG0 ];
    
    my $response_obj = $response->{response};
    my $response_err = $response->{error};

    unless ( defined $response_obj ) {
        $self->_log( v => 4, msg => 'resolution of '.$con->peer_hostname.' failed: '.$response_err  );
        $self->process_plugins( [ 'hostname_resolve_failed', $self, $con, $response_err ] );
        $con->close();
        return;
    }

    my @addr = map { $_->rdatastr } ( $response_obj->answer );

    # pick a random ip
    my $peer_ip = $addr[ int rand( @addr ) ];
    $self->_log( v => 4, msg => 'resolved '.$con->peer_hostname.' to '.join(',',@addr).' using: '.$peer_ip );

    $con->peer_ips( \@addr );

    $con->peer_ip( $peer_ip );
    $con->peer_addr( $peer_ip.':'.$con->peer_port );

    $self->reconnect( $con );

    return;
}

sub reconnect {
    my ( $self, $con ) = @_;

    # TODO include backoff

    $con->connected( 0 );

    $con->sf( undef );
    $con->wheel( undef );

    if ( $self->{opts}->{connect_time_out} ) {
        $con->{timeout_id} = $poe_kernel->alarm_set(
            $con->event( 'remote_connect_timeout' ),
            time() + $self->{opts}->{connect_time_out}
        );
    }

    $con->socket_factory(
        RemoteAddress => $con->{peer_ip},
        RemotePort    => $con->{peer_port},
        SuccessEvent  => $con->event( 'remote_connect_success' ),
        FailureEvent  => $con->event( 'remote_connect_error' ),
    );

    warn "connecting to $con";
    return $con;
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
