package POE::Component::Cometd::Connection;

use Class::Accessor;
use base qw(Class::Accessor);

__PACKAGE__->mk_accessors( qw( wheel connected close_on_flush transport ) );

sub new {
    my $class = shift;
    bless({
        @_
    }, ref $class || $class );
}

sub send {
    my $self = shift;
    if ( $self->wheel ) {
        $self->wheel->put(@_);
    } else {
        warn "cannot send data, where did my wheel go?! : $self->{dis_reason}";
    }
}

sub write {
    &send;
}

sub tcp_cork {
    # XXX
}

sub watch_write {
    # XXX
}

sub close {
    # TODO check out bytes and then
    $self->close_on_flush( 1 );
}


1;
