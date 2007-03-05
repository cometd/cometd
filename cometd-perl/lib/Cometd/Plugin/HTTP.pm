package Cometd::Plugin::HTTP;

use Cometd qw( Plugin );
use base 'Cometd::Plugin';

use POE qw( Filter::HTTPD Filter::Stream Wheel::ReadWrite Driver::SysRW );
use HTTP::Response;
use IO::AIO;
use HTTP::Date;
use Time::HiRes qw( time );
use MIME::Types;
use Scalar::Util qw( blessed );
use bytes;

use strict;
use warnings;


sub new {
    shift->SUPER::new(
        @_
    );
}


sub as_string {
    warn __PACKAGE__." should of been subclassed!";
    __PACKAGE__;
}


# called from aio_read
sub send_file {
    my ( $self, $server, $con, $file, $fh, $out, $size_out ) = @_;

    my $r = $con->{_r};
    my $size = $con->{_stat}->[ 7 ];

    if ( $size == $size_out ) {
        $r->content_type( $self->{mime}->type( $file ) );
        $self->finish( $con, $out, $size );
    } else {
        $server->_log( v=> 4, msg => "500 [$size != $size_out] [short read:$!] $file" );
        $r->code( 500 );
        $r->content_type( 'text/plain' );
        $self->finish( $con, 'ERROR: short read' );
    }
    close $fh if ($fh);
    
    return;
}


sub start_http_request {
    my ( $self, $server, $con, $req ) = @_;

    delete $con->{_close};

    $con->{_start_time} = time()
        unless ( defined $con->{_start_time} );

    if ( blessed( $req ) ) {
        if ( $req->isa( 'HTTP::Response' ) ) {
            $con->{_r} ||= $req; # a prebuilt http::response
            $con->{_req} ||= HTTP::Request->new();
#            $con->{_close} = 1;
            $self->finish( $con );
            return 0;
        } elsif ( $req->isa( 'HTTP::Request' ) ) {
            $con->wheel->pause_input(); # no more requests

            # can continue request handling
            return 1;
        }
    }

    $server->_log( v => 2, msg => "request isn't an HTTP object: $req" );
    $con->{_r} = HTTP::Response->new( 500 );
    $con->{_close} = 1;
    $self->finish( $con, 'invalid request' );

    # do not continue
    return undef;
}


sub finish {
    my ( $self, $con, $out, $size ) = @_;

    my $time = time();

    my $r = $con->{_r};

    # TODO version here
    $r->header( Server => 'Cometd' );
    $r->header( 'X-Powered-By' => 'Cometd (http://cometd.com/); POE (http://poe.perl.org/); Perl (http://perl.org/)' );
    $r->header( 'X-Time-To-Serve' => ( $time - $con->{_start_time} ) );
    $r->header( Date => time2str( $time ) );

    # TODO
    # in request:
    # TODO Accept-Encoding  gzip,deflate
    # TODO Keep-Alive: 300

    my $proto;
    $r->protocol( $proto = $con->{_req}->protocol );
    if ( $proto eq 'HTTP/1.0' ) {
        unless ( defined( $con->{_close} ) ) {
            my $connection = $con->{_req}->header( 'connection' );
            if ( $connection && $connection =~ m/^keep-alive$/i ) {
                $con->{_close} = 0;
                $r->header( 'Connection' => 'keep-alive' );
            } else {
                $con->{_close} = 1;
            }
        }
    } elsif ( $proto eq 'HTTP/1.1' ) {
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
        if ( ref( $out ) ) {
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
    
    if ( $con->{_close} ) {
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
    
    # TODO log full request`
    $self->_log(v => 1, msg => join( ' ',
        $con->{_req}->protocol,
        $r->code,
        sprintf( '%.5g', $r->header( 'X-Time-To-Serve' ) ),
        ( defined $size ? $size : '-' ),
        $con->{_req}->uri,
        "[$con->{_uri}]",
        ( $r->code == 302 ? $r->header( 'Location' ) : '' )
    ));

    return 1;
}


# private methods

sub resolve_path {
    my $self = shift;
    my $path = shift || '';

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
