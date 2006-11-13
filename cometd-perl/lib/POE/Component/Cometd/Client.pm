package POE::Component::Cometd::Client;

use strict;
use warnings;

use POE qw( Component::Cometd Filter::Stackable Filter::Stream );
use base qw( POE::Component::Cometd );
use overload '""' => \&as_string;

our $singleton;

sub spawn {
    return $singleton if ( $singleton );
    my $self = $singleton = shift->SUPER::new( @_ );
    
    POE::Session->create(
#       options => { trace=>1 },
       heap => $self,
       object_states => [
            $self => [ @{$self->{opts}->{base_states}}, qw(
                _start
                _stop

                _status_clients
                connect_to_client
                remote_connect_success
                remote_connect_timeout
                remote_connect_error
                remote_error
                remote_receive
                remote_flush
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

    $kernel->yield( '_status_clients' );
}

sub _stop {
    my $self = $_[OBJECT];
    $self->_log(v => 2, msg => $self->{opts}->{Name}." stopped.");
}

sub _status_clients {
    my $self = $_[OBJECT];
#    $_[KERNEL]->delay_set( _status_clients => 5 );
    $self->_log(v => 2, msg => $self->{opts}->{Name}." : in + out connections: $self->{connections}");
}


# TODO inc backoff
sub reconnect_to_client {
    my ( $self, $heap ) = @_;

    $self->{connections}-- if ( $heap->connected );
    $heap->connected( 0 );

    delete $heap->{sf};
    delete $heap->{wheel};

    if ( $self->{opts}->{ConnectTimeOut} ) {
        $heap->{timeout_id} = $poe_kernel->alarm_set(
            $self->create_event( $heap,'remote_connect_timeout' )
                => time() + $self->{opts}->{ConnectTimeOut}
        );
    }

    $heap->{sf} = POE::Wheel::SocketFactory->new(
        RemoteAddress => $heap->{peer_ip},
        RemotePort    => $heap->{peer_port},
        SuccessEvent  => $self->create_event( $heap,'remote_connect_success' ),
        FailureEvent  => $self->create_event( $heap,'remote_connect_error' ),
    );
    
}

sub connect_to_client {
    my ( $kernel, $self, $addr ) = @_[KERNEL, OBJECT, ARG0];

    my ( $address, $port ) = ( $addr =~ /^([^:]+):(\d+)$/ ) or return;
    
    # TODO resolve this addr that isn't an ip, non blocking
    my $heap = POE::Component::Cometd::Connection->new(
        peer_ip => $address,
        peer_port => $port,
        addr => $addr,
    );

    $self->add_heap( $heap );
   
    $self->reconnect_to_client( $heap );
    
    undef;
}

sub remote_connect_success {
    my ( $kernel, $self, $heap, $socket ) = @_[KERNEL, OBJECT, HEAP, ARG0];
    my $addr = $heap->{addr} = join( ':', @$heap{qw( peer_ip peer_port )} ); 
    $self->_log(v => 3, msg => $self->{opts}->{Name}." connected to $addr");

    $kernel->alarm_remove( delete $heap->{timeout_id} )
        if ( exists( $heap->{timeout_id} ) );
    
    $heap->wheel( POE::Wheel::ReadWrite->new(
        Handle       => $socket,
        Driver       => POE::Driver::SysRW->new(),
        Filter       => POE::Filter::Stackable->new(
            Filters => [
                POE::Filter::Stream->new(),
            ]
        ),
        InputEvent   => $self->create_event( $heap,'remote_receive' ),
        ErrorEvent   => $self->create_event( $heap,'remote_error' ),
#        FlushedEvent => $self->create_event( $heap,'remote_flush' ),
    ) );
    delete $heap->{sf};

    $self->{connections}++;

    $heap->connected( 1 );
    
    warn "connected";
    
    $self->{transport}->process_plugins( [ 'remote_connected', $self, $heap, $socket ] );
}

sub remote_connect_error {
    my ( $kernel, $self, $heap ) = @_[KERNEL, OBJECT, HEAP];

    $self->_log(v => 2, msg => $self->{opts}->{Name}." : Error connecting to $heap->{addr} : $_[ARG0] error $_[ARG1] ($_[ARG2])");

    $kernel->alarm_remove( delete $heap->{timeout_id} )
        if ( exists( $heap->{timeout_id} ) );

    $self->reconnect_to_client( $heap );
}

sub remote_connect_timeout {
    my ( $kernel, $self, $heap ) = @_[KERNEL, OBJECT, HEAP];
    
    $self->_log(v => 2, msg => $self->{opts}->{Name}." : timeout connecting to $heap->{addr}");

    $self->reconnect_to_client( $heap );

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
    my ( $event, $heap, $to_source ) = @_;
    
    foreach my $heap (keys %{$singleton->{heaps}}) {
        # $heap is a stringified version
        my $heap = $singleton->{heaps}->{$heap};
        warn "$heap is on $heap->{addr}";
        if ( $heap ) {
            if ( $to_source ) {
                warn "to source";
                # send only back to the source if requested
                next if ( $heap ne "$heap" );
                warn "sending only to the source";
            } else {
                # don't send back to the source
                #next if ( $heap eq "$heap" );
                warn "heap passed in";
            }
        }
        if ( $heap->connected && $heap->wheel ) {
            warn "putting event $event to $heap->{addr}";
            $heap->send( $event );
        }
    }
}



1;
