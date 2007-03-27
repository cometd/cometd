package Sprocket::Plugin::HTTP::Deny;

use Sprocket qw( Plugin::HTTP );
use base 'Sprocket::Plugin::HTTP';

use POE;
use HTTP::Response;
use HTTP::Date;

use strict;
use warnings;

sub new {
    my $class = shift;
    
    my $self = $class->SUPER::new(
        name => 'HTTP::Deny',
        @_
    );

    return $self;
}

sub as_string {
    __PACKAGE__;
}


# ---------------------------------------------------------
# server

sub local_connected {
    my ( $self, $server, $con, $socket ) = @_;
    $self->take_connection( $con );
    # POE::Filter::Stackable object:
    $con->filter->push( POE::Filter::HTTPD->new() );
    $con->filter->shift(); # pull off Filter::Stream
    return OK;
}

sub local_receive {
    my $self = shift;
    my ( $server, $con, $req ) = @_;

    $self->start_http_request( @_ ) or return OK;
    
    $con->{_req} ||= $req;
    $con->call( simple_response => 403 );
    
    $self->release_connection( $con );

    return OK;
}

1;
