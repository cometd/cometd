package Cometd::Plugin::JSONTransport;

use POE::Filter::Stackable;
use POE::Filter::JSON;

use strict;
use warnings;

sub BAYEUX_VERSION() { '1.0' }

sub new {
    my $class = shift;
    bless({
        filter => POE::Filter::Stackable->new(
            Filters => [
# XXX the cometd client/server should let us set 
# the filter on the rw wheel at some point
#                POE::Filter::Line->new(),
                POE::Filter::JSON->new()
            ]
        ),
        @_
    }, $class);
}

sub handle {
    my ( $self, $event ) = @_;
    if ( defined( __PACKAGE__ . '::' . $event ) ) {
        $self->$event( splice( @_, 2, $#_ ) );
    }
    return 1;
}

sub connected {
    my ( $self, $cheap, $socket, $wheel ) = @_;
    $cheap->{transport} = 'JSON';
    if ( $wheel ) {
        $wheel->put( "bayeux " . BAYEUX_VERSION );
    }
}

sub remote_input {
    my ($self, $cheap, $input) = @_;
    
    my $objs = $self->{filter}->get( [ $input ] );

    while ( my $obj = shift @$objs ) {
        $self->{chman}->deliver($cheap, $obj);
    }
}

1;
