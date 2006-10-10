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
            $self => [qw(
                _start
                _default
                _stop
                register
                unregister
                notify
                signals
                send

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

    $session->option( @{$self->{opts}{ClientSessionOptions}} )
        if ( $self->{opts}{ClientSessionOptions} ); 
    $kernel->alias_set( $self->{opts}{ClientAlias} )
        if ( $self->{opts}{ClientAlias} );

    $kernel->sig( INT => 'signals' );
    
    # connect to our client list
    if ( $self->{opts}{ClientList} 
        && ref( $self->{opts}{ClientList} ) eq 'ARRAY' ) {
        my $d = .1;
        foreach ( @{$self->{opts}{ClientList}} ) {
            $kernel->delay_set( connect_to_client => $d => $_ );
            $d += .05;
        }
    }

    $kernel->yield( '_status_clients' );
}

sub _stop {
    $_[OBJECT]->_log(v => 2, msg => "Client stopped.");
}

sub _status_clients {
    my $self = $_[OBJECT];
#    $_[KERNEL]->delay_set( _status_clients => 5 );
    $self->_log(v => 2, msg => "in + out connections: $self->{connections}");
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

sub reconnect_to_client {
    my ( $self, $cheap ) = @_;

    $cheap->{connected} = 0;

    delete $cheap->{sf};
    delete $cheap->{con};

    if ( $self->{opts}->{ConnectTimeOut} ) {
        $cheap->{timeout_id} = $poe_kernel->alarm_set(
            $self->create_event( $cheap,'remote_connect_timeout' )
                => time() + $self->{opts}->{ConnectTimeOut}
        );
    }

    $cheap->{sf} = POE::Wheel::SocketFactory->new(
        RemoteAddress => $cheap->{peer_ip},
        RemotePort    => $cheap->{peer_port},
        SuccessEvent  => $self->create_event( $cheap,'remote_connect_success' ),
        FailureEvent  => $self->create_event( $cheap,'remote_connect_error' ),
    );
    
}

sub connect_to_client {
    my ( $kernel, $self, $addr ) = @_[KERNEL, OBJECT, ARG0];

    my ( $address, $port ) = ( $addr =~ /^([^:]+):(\d+)$/ ) or return;

    my $cheap = {
        peer_ip => $address,
        peer_port => $port,
        addr => $addr,
    };

    $self->add_client_obj( $cheap );
   
    $self->reconnect_to_client( $cheap );
    
    undef;
}

sub remote_connect_success {
    my ( $kernel, $self, $socket ) = @_[KERNEL, OBJECT, ARG0];
    my $cheap = $self->{cheap};
    my $addr = $cheap->{addr} = join( ':', @$cheap{qw( peer_ip peer_port )} ); 
    $self->_log(v => 3, msg => "Connected to $addr");

    $kernel->alarm_remove( delete $cheap->{timeout_id} )
        if ( exists( $cheap->{timeout_id} ) );
    
    $cheap->{con} = POE::Wheel::ReadWrite->new(
        Handle       => $socket,
        Driver       => POE::Driver::SysRW->new(),
        Filter       => POE::Filter::Stackable->new(
            Filters => [
                POE::Filter::Stream->new(),
            ]
        ),
        InputEvent   => $self->create_event( $cheap,'remote_receive' ),
        ErrorEvent   => $self->create_event( $cheap,'remote_error' ),
#        FlushedEvent => $self->create_event( $cheap,'remote_flush' ),
    );
    delete $cheap->{sf};

    $self->{connections}++;

    $cheap->{connected} = 1;
    
    warn "connected";
    
    $self->{transport}->process_plugins( [ 'remote_connected', $cheap, $socket, $cheap->{con} ] );
}

sub remote_connect_error {
    my ( $kernel, $self ) = @_[KERNEL, OBJECT];

    my $cheap = $self->{cheap};

    $self->_log(v => 2, msg => "Erorr connecting to $cheap->{addr} : $_[ARG0] error $_[ARG1] ($_[ARG2])");

    $kernel->alarm_remove( delete $cheap->{timeout_id} )
        if ( exists( $cheap->{timeout_id} ) );

    $self->{connections}--;
    
    $self->reconnect_to_client( $cheap );
}

sub remote_connect_timeout {
    my ( $kernel, $self ) = @_[KERNEL, OBJECT];
    my $cheap = $self->{cheap};

    $self->_log(v => 2, msg => "Timeout connecting to $cheap->{addr}");

    $self->{connections}--;

    $self->reconnect_to_client( $cheap );

    undef;
}

sub remote_receive {
    my $self = $_[OBJECT];
    $self->_log(v => 4, msg => "got input ".$_[ARG0]);
    $self->{transport}->process_plugins( [ 'remote_receive', $self->{cheap}, $_[ARG0] ] );
}

sub remote_error {
    my $self = $_[OBJECT];
    $self->_log(v => 2, msg => "got error " . join( ' : ', @_[ARG1 .. ARG2] ) );
    
    $self->{connections}--;
    
    $self->reconnect_to_client( $self->{cheap} );
}

sub remote_flush {
#    $_[OBJECT]->_log(v => 2, msg => "got flush");

}

sub deliver_event {
    my ( $event, $cheap, $to_source ) = @_;
    
    foreach my $heap (keys %{$singleton->{heaps}}) {
        # $heap is a stringified version
        my $heap = $singleton->{heaps}->{$heap};
        warn "$heap is on $heap->{addr}";
        if ( $cheap ) {
            if ( $to_source ) {
                warn "to source";
                # send only back to the source if requested
                next if ( $heap ne "$cheap" );
                warn "sending only to the source";
            } else {
                # don't send back to the source
                #next if ( $heap eq "$cheap" );
                warn "cheap passed in";
            }
        }
        if ( $heap->{connected} && $heap->{con} ) {
            warn "putting event $event to $heap->{addr}";
            $heap->{con}->put( $event );
        }
    }
}



1;
