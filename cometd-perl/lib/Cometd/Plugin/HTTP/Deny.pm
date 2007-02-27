package Cometd::Plugin::HTTP::Deny;

use Cometd::Plugin;
use base 'Cometd::Plugin';

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
    my ( $self, $server, $con, $req ) = @_;

    my $r;
    
    if ( ref $req && $req->isa( 'HTTP::Response' ) ) {
        $r = $con->{_r} || $req; # a prebuilt response
        $con->{_close} = 1; # default anyway
    } elsif ( ref $req && $req->isa( 'HTTP::Request' ) ) {
        if ( !defined( $con->{_close} ) ) {
            my $connection = $req->header( 'connection' );
            $con->{_close} = 0 if ( $connection && $connection =~ m/^keep-alive$/i );
        }
    
        $con->{_uri} ||= $req->uri;

        $server->_log( v=> 4, msg => "403 [directory] $con->{_uri}" );
    
        $r = $con->{_r} || HTTP::Response->new();
        $r->header( Date => time2str( time() ) ) unless ( $r->header( 'Date' ) );
        $r->header( Server => 'Cometd (http://cometd.com/)' );
    
        $r->code( 403 );
        $r->content_type( 'text/html' );
        my $out = qq|<html><head><title>403 Forbidden</title></head><body><h2>403 Forbidden</h2></body></html>\n|;
        $r->header( 'Content-Length' => length( $out ) );
        $r->content( $out );
    }
    
    # XXX max requests
#    $con->{_close} = 1 if ( $con->{__requests} && $con->{__requests} > 100 );
   
    if ( $con->{_close} ) {
        $r->header( 'connection' => 'close' );
        $con->send( $r );
        $con->wheel->pause_input(); # no more requests
        $con->close();
    } else {
        # TODO set timeout based on keepalive: header
        $r->header( 'connection' => 'keep-alive' );
        $con->send( $r );
        $con->{__requests}++;
        $con->wheel->resume_input();
        # release control to other plugins
        $self->release_connection( $con );
    }

    return 1;
}

1;
