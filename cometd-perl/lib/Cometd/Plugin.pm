package Cometd::Plugin;

use strict;

use overload '""' => sub { shift->as_string(); };

sub as_string {
    warn "This cometd plugin should of been subclassed!";
    __PACKAGE__;
}

sub handle {
    my ( $self, $event ) = @_;
    if ( $self->can( $event ) ) {
        $self->$event( splice( @_, 2, $#_ ) );
    }
    return 1;
}

1;
