package Cometd::Plugin::JSONTransport;

use Cometd::Plugin;
use base 'Cometd::Plugin';

use POE::Filter::Line;
use POE::Filter::JSON;

use strict;
use warnings;

use constant BAYEUX_VERSION => '1.0';

sub new {
    my $class = shift;
    $class->SUPER::new(
        name => 'JSON',
        @_
    );
}

sub as_string {
    __PACKAGE__;
}

# ---------------------------------------------------------
# Client

sub remote_connected {
    my ( $self, $client, $con, $socket ) = @_;
    $self->take_connection( $con );
    # POE::Filter::Stackable object:
    my $filter = $con->filter;

    $filter->push( POE::Filter::Line->new() );

    $con->send( "bayeux " . BAYEUX_VERSION );
        
    $filter->push( POE::Filter::JSON->new() );
        
    # XXX should we pop the stream filter off the top?
    return 1;
}

sub remote_receive {
    my ($self, $client, $con, $event) = @_;
    $self->{chman}->deliver($con, $event);
    return 1;
}


# ---------------------------------------------------------
# server

sub local_connected {
    my ( $self, $server, $con, $socket ) = @_;

    $self->take_connection( $con );
    
    # POE::Filter::Stackable object
    $con->filter->push(
        POE::Filter::Line->new(),
        POE::Filter::JSON->new(),
    );
    # XXX should we pop the stream filter off the top?
    
    $con->send({ bayeux => BAYEUX_VERSION });
 
    return 1;
}

sub local_receive {
    my ($self, $server, $con, $event) = @_;
    $self->{chman}->deliver($con, $event);
    return 1;
}

1;
