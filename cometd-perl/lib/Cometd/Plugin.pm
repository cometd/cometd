package Cometd::Plugin;

use strict;

use overload '""' => sub { shift->as_string(); };

sub as_string {
    warn "This cometd plugin should of been subclassed!";
    __PACKAGE__;
}

sub handle {
    my ( $self, $event ) = @_;
    
    #$self->$event( @_[ 2, $#_ ] )
    $self->$event( splice( @_, 2, $#_ ) )
        if ( $self->can( $event ) );
    
    return 1;
}

# Plugins can define the following methods
# all are optional

# ---------------------------------------------------------
# server
# ---------------------------------------------------------
# local_connected
# local_receive
# local_disconnected

# ---------------------------------------------------------
# client
# ---------------------------------------------------------
# remote_connected
# remote_receive
# remote_disconnected (TODO not setup yet)

1;
