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
    return 1;
}

sub local_receive {
    my $self = shift;
    my ( $server, $con, $req ) = @_;

    $self->start_http_request( @_ ) or return 1;
    
    $con->{_req} = $req;
    my $r = $con->{_r} || HTTP::Response->new();
    $r->code( 403 );
    $r->content_type( 'text/html' );
    $con->call( finish => qq|<html><head><title>403 Forbidden</title></head><body><h2>403 Forbidden</h2></body></html>\n| );
    
    $self->release_connection( $con );

    return 1;
}

1;
