package Cometd::Plugin;

use strict;

use overload '""' => \&as_string;

sub as_string {
    __PACKAGE__;
}

sub handle {
    my ( $self, $event ) = @_;
    if ( defined( "$self::" . $event ) ) {
        $self->$event( splice( @_, 2, $#_ ) );
    }
    return 1;
}

1;
