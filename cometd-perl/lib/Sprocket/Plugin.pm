package Sprocket::Plugin;

use Class::Accessor::Fast;
use base qw(Class::Accessor::Fast);
use Scalar::Util qw( weaken );
use POE;
use Sprocket;

__PACKAGE__->mk_accessors( qw( name parent_id ) );

use overload '""' => sub { shift->as_string(); };

use strict;
use warnings;

sub new {
    my $class = shift;
    bless( {
        &adjust_params
    }, ref $class || $class );
}

sub as_string {
    warn "This Sprocket plugin should have been subclassed!";
    __PACKAGE__;
}

sub handle_event {
    my ( $self, $event ) = ( shift, shift );
    
    return $self->$event( @_ )
        if ( $self->can( $event ) );
    
    return undef;
}

sub _log {
    $poe_kernel->call( shift->parent_id => _log => ( call => ( caller(1) )[ 3 ], @_ ) );
}

sub take_connection {
    my ( $self, $con ) = @_;
    $con->plugin( $self->name );
    return 1;
}

sub release_connection {
    my ( $self, $con ) = @_;
    $con->plugin( undef );
    return 1;
}

sub time_out {
    my ( $self, $server, $con, $time ) = @_;
    $server->_log( v => 4, msg => "Timeout for connection $con" );
    $con->close();
    return 1;
}

sub local_accept {
    my ( $self, $server, $con, $socket ) = @_;
    $con->accept();
    return 1;
}

# Plugins can define the following methods
# all are optional

# ---------------------------------------------------------
# server
# ---------------------------------------------------------
# local_accept
# local_connected
# local_receive
# local_disconnected

# ---------------------------------------------------------
# client
# ---------------------------------------------------------
# remote_connected
# remote_receive
# remote_disconnected

1;
