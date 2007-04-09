package Sprocket::Plugin::HTTP;

use Sprocket qw( Plugin );
use base 'Sprocket::Plugin';

use POE qw( Filter::HTTPD Filter::Stream Wheel::ReadWrite Driver::SysRW );
use HTTP::Response;
use IO::AIO;
use HTTP::Date;
use HTTP::Status qw( status_message is_info RC_BAD_REQUEST );
use Time::HiRes qw( time );
use MIME::Types;
use Scalar::Util qw( blessed );
use bytes;

use strict;
use warnings;

sub OK()    { 1 }
sub DEFER() { 0 }
sub BAD()   { undef }

our %simple_responses = (
    403 => 'Forbidden',
    404 => 'The requested URL was not found on this server.',
    500 => 'A server error occurred',
);

sub new {
    shift->SUPER::new(
        mime => MIME::Types->new(),
        @_
    );
}


sub import {
    my $self = shift;

    my $package = caller();
    my @exports = qw(
        OK
        DEFER
        BAD
    );

    push( @exports, @_ ) if ( @_ );
    
    no strict 'refs';
    foreach my $sub ( @exports ) {
        *{ $package . '::' . $sub } = \&$sub;
    }
}


sub as_string {
    warn __PACKAGE__." should of been subclassed!";
    __PACKAGE__;
}


# called from aio_read
sub send_file {
    my ( $self, $server, $con, $file, $fh, $out, $size_out ) = @_;

    my $r = $con->{_r};
    my $size = $con->{__stat}->[ 7 ];

    if ( $size == $size_out ) {
        $r->content_type( $self->{mime}->type( $file ) );
        $con->call( finish => $out => $size );
    } else {
        $server->_log( v=> 4, msg => "500 [$size != $size_out] [short read:$!] $file" );
        $r->code( 500 );
        $r->content_type( 'text/plain' );
        $con->call( finish => 'ERROR: short read' );
    }
    close $fh if ($fh);
    
    return OK;
}

sub simple_response {
    my ( $self, $server, $con, $code, $extra ) = @_;

    $code ||= 500;

    # XXX do something else with status?
    my $status = status_message( $code ) || 'Unknown Error';
    my $r = $con->{_r} ||= HTTP::Response->new();
    $r->code( $code );

    if ( $code == 301 || $code == 302 ) {
        $r->header( Location => $extra || '/' );
        $con->call( finish => '' );
        return;
    } elsif ( is_info( $code ) ) {
        $con->call( finish => '' );
        return;
    }

    my $body = $simple_responses{ $code } || $status;

    if ( defined $extra ) {
        $body .= '<p>'.$extra;
    }

    $r->content_type( 'text/html' );
    $con->call( finish => qq|<!DOCTYPE HTML PUBLIC "-//IETF//DTD HTML 2.0//EN">
<html>
    <head>
        <title>$code $status</title>
    </head>
    <body>
        <h1>$status</h1>
        $body
    </body>
</html>| );
}


sub start_http_request {
    my ( $self, $server, $con, $req ) = @_;

    delete $con->{_close};

    $con->{_start_time} = time()
        unless ( $con->{_start_time} );

    if ( blessed( $req ) ) {
        if ( $req->isa( 'HTTP::Response' ) ) {
            $con->{_r} ||= $req; # a prebuilt http::response
            $con->{_req} ||= HTTP::Request->new();
#            $con->{_close} = 1;
            $con->call( 'finish' );
            return DEFER;
        } elsif ( $req->isa( 'HTTP::Request' ) ) {
            $con->wheel->pause_input(); # no more requests
            $con->{_req} ||= $req;
            # can continue request handling
            return OK;
        }
    }

    $server->_log( v => 2, msg => "request isn't an HTTP object: $req" );
    $con->{_r} = HTTP::Response->new( 500 );
    $con->{_close} = 1;
    $con->call( finish => 'invalid request' );

    # do not continue
    return BAD;
}


sub finish {
    my ( $self, $server, $con, $out, $size ) = @_;

    my $time = time();

    my $r = $con->{_r} || HTTP::Response->new( 500 );

    # TODO real version here
    $r->header( Server => 'Sprocket/1.0' );
    $r->header( 'X-Powered-By' => 'Sprocket (http://sprocket.xantus.org/); '
        . 'Cometd (http://cometd.com/); POE (http://poe.perl.org/); Perl (http://perl.org/)' );
    $r->header( 'X-Time-To-Serve' => ( $time - $con->{_start_time} ) );
    $r->header( Date => time2str( $time ) );

    # TODO
    # in request:
    # TODO Accept-Encoding  gzip,deflate
    # TODO Keep-Alive: 300

    my $proto;
    $r->protocol( $proto = $con->{_req}->protocol );
    if ( $proto && $proto eq 'HTTP/1.0' ) {
        unless ( defined( $con->{_close} ) ) {
            my $connection = $con->{_req}->header( 'connection' );
            if ( $connection && $connection =~ m/^keep-alive$/i ) {
                $con->{_close} = 0;
                $r->header( 'Connection' => 'keep-alive' );
            } else {
                $con->{_close} = 1;
            }
        }
    } elsif ( $proto && $proto eq 'HTTP/1.1' ) {
        # in 1.1, keep-alive is assumed
        $con->{_close} = 0
            unless ( defined( $con->{_close} ) );
    } else {
        # XXX
        $con->{_close} = 1;
    }

    # XXX max requests, not needed
#    $con->{_close} = 1 if ( $con->{__requests} && $con->{__requests} > 100 );
   
    if ( defined( $out ) ) {
        if ( ref( $out ) && ref( $out ) eq 'SCALAR' ) {
            # must pass size if passing scalar ref
            $r->content( $$out );
        } else {
            $size = length( $out ) unless ( $size );
            $r->content( $out );
        }
        $r->header( 'Content-Length' => $size );
    }


    # XXX check for content length if keep-alive? 
    
    if ( my $clid = $con->clid ) {
        $r->header( 'X-Client-ID' => $clid );
    }
    
    $r->header( 'X-Sprocket-CID' => $con->ID );
    
    if ( $con->{_close} ) {
        warn "closing $con";
        $r->header( 'Connection' => 'close' );
        $con->wheel->pause_input(); # no more requests
        $con->send( $r );
        $con->close();
    } else {
        # TODO set/reset timeout
        $con->send( $r );
        $con->{__requests}++;
        $con->wheel->resume_input();
    }
    #return OK;
    
    # TODO log full request`
    $server->_log(v => 1, msg => join( ' ',
        ( $con->{_req} ? $con->{_req}->protocol : '?' ),
        $r->code,
        ( $r->header( 'X-Time-To-Serve' ) ? sprintf( '%.5g', $r->header( 'X-Time-To-Serve' ) ) : '?' ),
        ( defined $size ? $size : '-' ),
        ( $con->{_req} ? $con->{_req}->uri : '?' ),
        ( $con->{_uri} ? "[$con->{_uri}]" : "" ),
        ( $r->code && $r->code == 302 ? $r->header( 'Location' ) : '' )
    ));

    return OK;
}


# private methods

sub resolve_path {
    my $self = shift;
    my $path = shift || '';

    $path =~ s/\0//g;

    my $cwd = '/';
    my $path_out = '';

    if ($path eq '') {
        $path_out = $cwd;
    } elsif ($path eq '/') {
        $path_out = '/';
    } else {
        my @real = split( m!/!, $cwd );
        if ( $path =~ m!^/! ) {
            undef @real;
        }
        foreach ( split( m!/!, $path ) ) {
            if ( $_ eq '..' ) {
                pop( @real ) if ( $#real );
            } elsif ( $_ eq '.' ) {
                next;
            } elsif ( $_ eq '~' ) {
                if ( $self->{home_path} ) {
                    @real = split( m!/!, $self->{home_path} );
                } else {
                    next;
                }
            } else {
                push( @real, $_ );
            }
        }
        $path_out = join( '/', @real );
    }
    
    $path_out = ( $path_out =~ m!^/! ) ? $path_out : '/'.$path_out;
    $path_out .= ( $path_out =~ m!/$! ) ? '' : '/'
        if ( $path =~ m!/$! );

    return $path_out;
}


1;
