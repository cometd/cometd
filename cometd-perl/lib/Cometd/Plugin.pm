package Cometd::Plugin;

use Class::Accessor::Fast;
use base qw(Class::Accessor::Fast);
use Scalar::Util qw( weaken );
use POE;
use Cometd;

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
    warn "This cometd plugin should have been subclassed!";
    __PACKAGE__;
}

sub handle_event {
    my ( $self, $event ) = @_;
    
    #$self->$event( @_[ 2, $#_ ] )
    return $self->$event( splice( @_, 2, $#_ ) )
        if ( $self->can( $event ) );
    
    return undef;
}

sub _log {
    $poe_kernel->call( shift->parent_id => _log => @_ );
}

sub take_connection {
    my ($self, $con) = @_;
    $con->plugin( $self->name );
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
# remote_disconnected

1;
