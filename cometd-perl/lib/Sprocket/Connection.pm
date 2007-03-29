package Sprocket::Connection;

use POE qw( Wheel::SocketFactory Wheel::ReadWrite );
use Sprocket qw( Event );
use Class::Accessor::Fast;
use Time::HiRes qw( time );
use base qw( Class::Accessor::Fast );

use Scalar::Util qw( weaken );

__PACKAGE__->mk_accessors( qw(
    sf
    wheel
    socket
    connected
    close_on_flush
    plugin
    active_time
    create_time
    parent_id
    event_manager
    fused
    peer_ip
    peer_ips
    peer_port
    peer_addr
    peer_hostname
    state
    time_out
    ID
) );

our %callback_ids;

sub new {
    my $class = shift;
    my $time = time();

    my $self = bless({
        sf => undef,
        wheel => undef,
        connected => 0,
        close_on_flush => 0,
        plugin => undef,
        active_time => $time,
        create_time => $time,
        parent_id => undef,
        event_manager => 'eventman', # XXX
        fused => undef,
        peer_ip => undef,
        peer_port => undef,
        state => undef,
        channels => {},
        alarms => {},
        clid => undef,
        destroy_events => {},
        peer_ips => [],
        socket => undef,
        @_
    }, ref $class || $class );

    # generate the connection ID
    $self->ID( ( "$self" =~ m/\(0x([^\)]+)\)/o )[ 0 ] );

    # XXX keep this?
    if ( $self->{peer_ip} && !@{$self->{peer_ips}} ) {
        push( @{$self->{peer_ips}}, $self->{peer_ip} );
    }

    return $self;
}

sub event {
    my ( $self, $event ) = @_;

    return $self->ID.'/'.$event;
}

sub socket_factory {
    my $self = shift;

    $self->sf(
        POE::Wheel::SocketFactory->new( @_ )
    );
}

sub wheel_readwrite {
    my $self = shift;

    $self->wheel(
        POE::Wheel::ReadWrite->new( @_ )
    );
}

sub filter {
    my $self = shift;

    if ( @_ ) {
        $self->wheel->set_filter( @_ );
    }

    return $self->wheel->get_input_filter;
}

sub filter_in {
    my $self = shift;

    if ( @_ ) {
        $self->wheel->set_input_filter( @_ );
    }

    return $self->wheel->get_input_filter;
}

sub filter_out {
    my $self = shift;

    if ( @_ ) {
        $self->wheel->set_output_filter( @_ );
    }

    return $self->wheel->get_output_filter;
}

*write = *send;

sub send {
    my $self = shift;

    if ( my $wheel = $self->wheel ) {
        $self->active();
        $wheel->put(@_);
    } else {
        # XXX does this happen
        $self->_log( v => 1, msg => "cannot send data, where did my wheel go?!".
            ( $self->{dis_reason} ? $self->{dis_reason} : '' ) );
    }
}

sub set_time_out {
    my $self = shift;
    $self->time_out( shift );

    $self->active();
}

sub alarm_remove {
    my $self = shift;

    $poe_kernel->alarm_remove( @_ );
}

sub alarm_set {
    my $self = shift;
    my $event = $self->event( shift );

    my $id = $poe_kernel->alarm_set( $event => @_ );
    $self->{alarms}->{ $id } = $event;

    return $id;
}

sub alarm_adjust {
    my $self = shift;

    $poe_kernel->alarm_adjust( @_ );
}

sub alarm_remove {
    my $self = shift;
    my $id = shift;

    delete $self->{alarms}{ $id };
    $poe_kernel->alarm_remove( $id => @_ );
}

sub alarm_remove_all {
    my $self = shift;

    foreach ( keys %{$self->{alarms}} ) {
        $self->_log( v => 4, "removed alarm $_ for client" );
        $poe_kernel->alarm_remove( $_ );
    }

    return;
}

sub delay_set {
    my $self = shift;

    $poe_kernel->delay_set( $self->event( shift ) => @_ );
}

sub delay_adjust {
    my $self = shift;

    $poe_kernel->delay_adjust( @_ );
}

sub yield {
    my $self = shift;

    $poe_kernel->post( $self->parent_id => $self->event( shift ) => @_ );
}

sub call {
    my $self = shift;

    $poe_kernel->call( $self->parent_id => $self->event( shift ) => @_ );
}

sub post {
    shift;

    $poe_kernel->post( @_ );
}

sub fuse {
    my ( $self, $con ) = @_;

    $self->active();
    
    $self->fused( $con );
    weaken( $self->{fused} );

    $con->fused( $self );
    weaken( $con->{fused} );

    # TODO some code to fuse the socket or other method
    return;
}


sub accept {
    my $self = shift;

    $poe_kernel->call( $self->parent_id => $self->event( 'accept' ) => @_ );
}

sub reject {
    my $self = shift;
    $self->close( 1 );
#    $poe_kernel->call( $self->parent_id => $self->event( 'reject' ) => @_ );
}

sub close {
    my ( $self, $force ) = @_;

    $self->active();

    if ( my $wheel = $self->wheel ) {
        my $out = $wheel->get_driver_out_octets;

        if ( !$force && $out ) {
            $self->close_on_flush( 1 );
            return;
        } else {
            $wheel->shutdown_input();
            $wheel->shutdown_output();
        }
    }
    
    $self->wheel( undef )
        if ( $force );

    $self->time_out( undef );
    $self->connected( 0 );

    # kill the socket factory if any
    $self->sf( undef );
    
    if ( my $socket = $self->socket ) {
        close( $socket );
    }
    $self->socket( undef );

    if ( my $con = $self->fused() ) {
        $con->close( $force );
        $self->fused( undef );
    }

    $poe_kernel->call( $self->parent_id => cleanup => $self->ID );

    return;
}

sub get_driver_out_octets {
    my $self = shift;

    if ( my $wheel = $self->{wheel} ) {
        return $wheel->get_driver_out_octets();
    }

    return undef;
}

sub active {
    shift->active_time( time() );
}

sub callback {
    my ($self, $event, @etc) = @_;

    my $id = $self->parent_id;
    $event = $self->event( $event );

    my $callback = Sprocket::Connection::AnonCallback->new(sub {
        $poe_kernel->call( $id =>$event => @etc => @_ );
    });

    $callback_ids{$callback} = $id;

    $poe_kernel->refcount_increment( $self->{parent_id}, 'anon_event' );

    return $callback;
}

sub postback {
    my ($self, $event, @etc) = @_;

    my $id = $self->parent_id;
    $event = $self->event( $event );

    my $postback = Sprocket::Connection::AnonCallback->new(sub {
        $poe_kernel->post( $id => $event => @etc => @_ );
        return 0;
    });

    $callback_ids{$postback} = $id;

    $poe_kernel->refcount_increment( $self->{parent_id}, 'anon_event' );

    return $postback;
}

sub add_client {
    my $self = shift;

    if ( $self->clid && $self->event_manager ) {
        $self->{destroy_events}->{remove_client} = $poe_kernel->call(
            $self->event_manager => add_client => $self->clid => $self->ID
        );
    }
}

sub remove_client {
    my $self = shift;

    $poe_kernel->call( $self->event_manager => remove_client => $self->clid )
        if ( $self->clid && $self->event_manager );
}

sub add_channels {
    my $self = shift;

    if ( my ( $channels ) = @_ ) {
        foreach ( @$channels ) {
            $self->{channels}->{$_} = 1;
        }

        $poe_kernel->call( $self->event_manager => add_channels => $self->clid => $channels )
            if ( $self->clid && $self->event_manager );
    }

    return ( keys %{$self->{channels}} );
}

sub remove_channels {
    my $self = shift;

    if ( my ( $channels ) = @_ ) {
        foreach ( @$channels ) {
            delete $self->{channels}->{$_};
        }

        $poe_kernel->call( $self->event_manager => remove_channels => $self->clid => $channels )
            if ( $self->clid && $self->event_manager );
    }

    return ( keys %{$self->{channels}} );
}

sub clid {
    my ( $self, $clid ) = @_;

    if ( $clid ) {
        $self->{clid} = $clid;
        $self->add_client();
    }

    return $self->{clid};
}

sub send_event {
    my $self = shift;

    $poe_kernel->call( $self->event_manager => deliver_events => Sprocket::Event->new( clientId => $self->clid => @_ ) );
}

sub get_events {
    my $self = shift;

    $poe_kernel->call( $self->event_manager => get_events => $self->clid => [
        $self->parent_id, $self->event( 'events_received' )
    ] );
}

sub _log {
    my $self = shift;

    $poe_kernel->call( $self->parent_id => _log => ( l => 1, @_ ) );
}

# Danga::Socket type compat
# ------------------------

sub tcp_cork {
    # XXX is this the same as watch_read(0)?
}

sub watch_write {
    my ( $self, $watch ) = @_;

    $self->active();

    if ( my $wheel = $self->wheel ) {
        if ( $watch ) {
            $wheel->resume_output();
        } else {
            $wheel->pause_output();
        }
    } # XXX else

    return;
}

sub watch_read {
    my ( $self, $watch ) = @_;

    $self->active();

    if ( my $wheel = $self->wheel ) {
        if ( $watch ) {
            $wheel->resume_input();
        } else {
            $wheel->pause_input();
        }
    } # XXX else

    return;
}

# ------------------------

sub DESTROY {
    my $self = shift;

#    warn "destruction of connection ".$self->ID;

    if ( keys %{$self->{destroy_events}} ) {
        foreach my $type ( keys %{$self->{destroy_events}} ) {
#            warn "events firing for $type:".Data::Dumper->Dump([ $self->{destroy_events}->{$type} ]);
            $poe_kernel->post( @{$self->{destroy_events}->{$type}} );
        }
    }

    # remove alarms for this connection
    foreach ( keys %{$self->{alarms}} ) {
        $self->_log( v => 4, "removed alarm $_ for client" );
        $poe_kernel->alarm_remove( $_ );
    }
    
    return;
}

1;

package Sprocket::Connection::AnonCallback;

use POE;

sub new {
    my $class = shift;

    bless( shift, $class );
}

sub DESTROY {
    my $self = shift;
    my $parent_id = delete $Sprocket::Connection::callback_ids{"$self"};

    if ( defined $parent_id ) {
        $poe_kernel->refcount_decrement( $parent_id, 'anon_event' );
    } else {
        warn "connection callback DESTROY without session_id to refcount_decrement";
    }

    return;
}



1;
