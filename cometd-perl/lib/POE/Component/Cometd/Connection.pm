package POE::Component::Cometd::Connection;

use POE qw( Wheel::SocketFactory );
use Class::Accessor;
use base qw(Class::Accessor);

__PACKAGE__->mk_accessors( qw( sf wheel connected close_on_flush transport ) );

sub new {
    my $class = shift;
    bless({
        @_
    }, ref $class || $class );
}

sub event {
    my ( $self, $event ) = @_;
    return "$self|$event";
}

sub ID {
    my $self = shift;
    return "$self";
}

sub socket_factory {
    my $self = shift;
    $self->sf( 
        POE::Wheel::SocketFactory->new( @_ )
    );
}

sub send {
    my $self = shift;
    if ( my $wheel = $self->wheel ) {
        $wheel->put(@_);
    } else {
        warn "cannot send data, where did my wheel go?!".
            ( $self->{dis_reason} ? $self->{dis_reason} : '' );
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
}

sub close {
    my ( $self, $force ) = @_;
    
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
    
    my $out = $self->wheel->get_driver_out_octets;
    if ( $out ) {
        $self->close_on_flush( 1 );
    } else {
        if ( my $wheel = $self->wheel ) {
            $wheel->shutdown_input();
            $wheel->shutdown_output();
        } # XXX else
        $self->connected( 0 );
    }
}

1;
