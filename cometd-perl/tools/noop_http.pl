#!/usr/bin/perl

use lib qw( lib );

# use this before POE, so Sprocket loads the Epoll loop if we have it
use Sprocket qw( Client Server );
use POE;

my %opts = (
    LogLevel => 4,
    TimeOut => 0,
    MaxConnections => 32000,
);

# comet http server
Sprocket::Server->spawn(
    %opts,
    Name => 'Noop Server',
    ListenPort => 8002,
    ListenAddress => '0.0.0.0',
    Plugins => [
        {
            Plugin => Sprocket::Plugin::Noop->new(),
            Priority => 0,
        },
    ],
);


$poe_kernel->run();

1;


package Sprocket::Plugin::Noop;

use Sprocket qw( Plugin );
use base 'Sprocket::Plugin';

use POE;
use POE::Filter::HTTPD;
use HTTP::Response;

use strict;
use warnings;

sub new {
    my $class = shift;
    $class->SUPER::new(
        name => 'Noop',
        @_
    );
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
#    $con->filter->shift(); # POE::Filter::Stream
    return 1;
}

sub local_receive {
    my ( $self, $server, $con, $req ) = @_;
    
    # TODO $req can be a HTTP::Response
    my $r = HTTP::Response->new( 200 );
    $r->content( 'OK' );
    $r->header( 'content-length' => 2 );
    my $close = 1;
    if ( my $c = $req->header( 'connection' ) ) {
        if ( $c =~ m/^keep-alive/i ) {
            $r->header( 'connection' => 'keep-alive' );
            $close = 0;
        }
    }
    $con->send( $r );
    
    $con->close() if ( $close );
    
    return 1;
}

1;
