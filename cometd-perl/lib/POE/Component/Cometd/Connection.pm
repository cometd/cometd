package POE::Component::Cometd::Connection;

use POE qw( Wheel::SocketFactory Wheel::ReadWrite );
use Class::Accessor::Fast;
use base qw(Class::Accessor::Fast);

__PACKAGE__->mk_accessors( qw( sf wheel connected close_on_flush plugin active_time parent_id ) );

sub new {
    my $class = shift;
    bless({
        @_
    }, ref $class || $class );
}

sub event {
    my ( $self, $event ) = @_;
    return $self->ID."|$event";
}

sub ID {
    my $self = shift;
    return ( "$self" =~ m/\(0x([^\)]+)\)/o )[ 0 ];
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
    return shift->wheel->get_input_filter;
}

sub send {
    my $self = shift;
    if ( my $wheel = $self->wheel ) {
        $self->active();
        $wheel->put(@_);
    } else {
        $self->_log( v => 1, msg => "cannot send data, where did my wheel go?!".
            ( $self->{dis_reason} ? $self->{dis_reason} : '' ) );
    }
}

sub write {
    &send;
}

sub tcp_cork {
    # XXX is this the same as watch_read(0)?
}

sub watch_write {
    my ( $self, $watch ) = @_;
    if ( my $wheel = $self->wheel ) {
        if ( $watch ) {
            $wheel->resume_output();
        } else {
            $wheel->pause_output();
        }
    } # XXX else
}

sub watch_read {
    my ( $self, $watch ) = @_;
    if ( my $wheel = $self->wheel ) {
        if ( $watch ) {
            $wheel->resume_input();
        } else {
            $wheel->pause_input();
        }
    } # XXX else
    if ( $force ) {
        if ( my $wheel = $self->wheel ) {
            $wheel->shutdown_input();
            $wheel->shutdown_output();
        }
        # kill the wheel
        $self->wheel( undef );
        $self->connected( 0 );
        # kill the socket factory if any
        $self->sf( undef );
        return;
    }
    
}

sub close {
    my ( $self, $force ) = @_;
    
    if ( my $wheel = $self->wheel ) {
        my $out = $wheel->get_driver_out_octets;
        if ( !$force && $out ) {
            $self->close_on_flush( 1 );
        } else {
            $wheel->shutdown_input();
            $wheel->shutdown_output();
            $self->wheel( undef )
                if ( $force );
            $self->connected( 0 );
            # kill the socket factory if any
            $self->sf( undef );
        }
    }
}

sub active {
    shift->active_time( time() );
}

sub _log {
    $poe_kernel->call( $self->parent_id => _log => ( l => 1, @_ ) );
}

1;
