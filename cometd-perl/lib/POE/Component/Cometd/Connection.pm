package POE::Component::Cometd::Connection;

use Class::Accessor;
use base qw(Class::Accessor);

__PACKAGE__->mk_accessors( qw( wheel connected close_on_flush ) );

sub new {
    my $class = shift;
    bless({
        @_
    }, $class );
}

sub send {
    my $self = shift;
    $self->wheel->put(@_);
}



1;
