package Cometd::Plugin::HTTPProxy;

use Cometd::Plugin;
use base 'Cometd::Plugin';

use POE qw( Filter::HTTPD Component::Client::HTTP );
use HTTP::Response;

use Data::Dumper;

use strict;
use warnings;

sub new {
    my $class = shift;
    
    my $self = $class->SUPER::new(
        name => 'HTTP Proxy Server',
        allow_list => {
        },
        deny_list => {
        },
        @_
    );
    
    POE::Session->create(
        object_states => [
            $self => [qw(
                _start
                _stop

                response
            )],
        ]
    );
    # TODO verify client

    return $self;
}

sub add_plugin {
}

sub as_string {
    __PACKAGE__;
}

sub _start {
    my $self = $_[OBJECT];
    $_[KERNEL]->alias_set( "$self" );
    $self->_log(v => 1, msg => 'started');
    
    POE::Component::Client::HTTP->spawn(
#        Agent     => 'SpiffCrawler/0.90',   # defaults to something long
        Alias     => 'http',                  # defaults to 'weeble'
        Timeout   => 60,                    # defaults to 180 seconds
#        MaxSize   => 16384,                 # defaults to entire response
#        Streaming => 4096,                  # defaults to 0 (off)
    );
}

sub _stop {
    my $self = $_[OBJECT];
    $self->_log(v => 1, msg => 'stopped');
}

sub response {
    my ( $self, $server, $con, $req, $res) = @_;
    
    if ( my $ce = $res->[0]->header( 'Content-Encoding' ) ) {
        $res->[0]->header( 'Content-Encoding' => '' )
            if ( $ce =~ m/gzip|deflate/ );
    }
    $res->[0]->header( 'Content-Length' => length( $res->[0]->content() ) );
    $con->send( $res->[0]->as_string() );
#    $con->filter->push( POE::Filter::HTTPD->new() );
#    $con->wheel->resume_input();
    $con->close();
}

# ---------------------------------------------------------
# server
# ---------------------------------------------------------

sub local_connected {
    my ( $self, $server, $con, $socket ) = @_;
    $self->take_connection( $con );
    # POE::Filter::Stackable object:
    $con->filter->push( POE::Filter::HTTPD->new() );
    #con->filter->shift(); # pull off Filter::Stream
    return 1;
}

sub local_receive {
    my ( $self, $server, $con, $req ) = @_;

    if ( my $fused = $con->fused ) {
        $self->_log( v => 4, msg => "local received" );
        $fused->send( $req ); # server -> client
        return;
    }
    
    my ( $out, $r );

    my $close = 1;
    
    if ( $req->isa( 'HTTP::Response' ) ) {
        $r = $req; # a prebuilt response
    } elsif ( $req->isa( 'HTTP::Request' ) ) {
        if ( !$self->{deny_list}{ $con->peer_ip } && $self->{allow_list}{ $con->peer_ip } ) {
            if ( $req->method eq 'CONNECT' && $req->uri =~ m/^([^:]+):(\d+)$/ ) {
                my ( $host, $port ) = ( $1, $2 );
                $self->_log( v => 4, msg => 'method: '.$req->method.' uri:'.$req->uri." host:$host port:$port" );
                $con->wheel->pause_input();
                my $newcon = $self->{client}->connect( $host, $port );
                $con->fuse( $newcon );
                return 1;
            } else {
                my $uri = $req->uri;
                $uri =~ s/^https?:\/\/[^\/]+\//\//;
                #if ( $uri =~ /https?:\/\// || !$req->header( 'host' ) ) {
                if ( $uri =~ /https?:\/\// ) {
                    # XXX correct code
                    $r = HTTP::Response->new( 400 );
                    $r->content_type( 'text/plain' );
                    $r->content( "Bad Request" );
                    $r->header( 'content-length' => 11 );
                } else {
                    #$req->uri( $uri );
                    $self->_log( v => 4, msg => 'request for '.$req->uri );
                    
                    $con->wheel->pause_input();
                    $con->{_oneoff} = 1;
                    $con->filter->pop(); # Filter::HTTP
                    
                    $poe_kernel->post( 'http' => 'request' => $con->event( 'response' ) => $req );
                    
                    return 1;

                    $req->header( 'connection' => 'close' );
                
                    my $newcon = $self->{client}->connect( $req->header( 'host' ), 80 );
                    $con->fuse( $newcon );
                    $req->protocol( "HTTP/1.0" );
                    $newcon->{_req} = $req;
                    $newcon->{_oneoff} = 1;
                    
                    #my $connection = $req->header( 'proxy-connection' );
                    #return if ( $connection && $connection =~ m/^keep-alive$/i );
                    # TODO pause
                    return;
               }
            }
        } else {
            # XXX correct code 
            $server->_log( v => 4, msg => 'Unauthorized connect: '.$req->uri );
            $r = HTTP::Response->new( 400 );
            $r->content( "Unauthorized" );
            $close = 1;
        }
    } else {
        $r = HTTP::Response->new( 501 );
        $r->content( "Not Implemented" );
        $r->header( 'content-length' => 15 );
        $close = 1;
    }

    if ( $close ) {
       $r->header( 'connection' => 'close' );
       $con->wheel->pause_input(); # no more requests
       $con->send( $r );
       $con->close();
    } else {
        # timeout?
        $r->header( 'connection' => 'keep-alive' );
        $con->send( $r );
        $con->{__requests}++;
    }
    
    return 1;
}

# ---------------------------------------------------------
# client
# ---------------------------------------------------------

sub remote_connected {
    my ( $self, $server, $con, $socket ) = @_;
    
    $self->take_connection( $con );
    
    $self->_log( v => 4, msg => "connected" );

    unless ( $con->fused ) {
        $self->_log( v => 4, msg => "the other fused connection is gone!" );
        $con->close(1);
        return;
    }
    
    if ( $con->{_oneoff} ) {
        $con->send( $con->{_req} );
        return;
    } else {
        $con->fused->filter->pop(); # Filter::HTTP
        # we are left with a stream filter on the stack
        $con->fused->send( "200 Connection Established\r\nX-HTTP-Server: Cometd\r\n"
            ."X-Connection-ID: ".$con->ID."-".$con->fused->ID."\r\n\r\n" );
        $con->fused->wheel->resume_input();
    }
}

sub remote_receive {
    my ( $self, $server, $con, $data ) = @_;
    
    $self->_log( v => 4, msg => "remote received" );
    
    if ( my $fused = $con->fused ) {
        $fused->send( $data ); # client => server
        if ( $con->{_oneoff} ) {
            $con->close();
            $con->fused->wheel->resume_input();
        }
    }
}

sub remote_disconnected {
    my ( $self, $server, $con, $socket ) = @_;
    
    $self->_log(v => 4, msg => 'client disconnected');
    
    if ( my $fused = $con->fused ) {
        $fused->close();
    }
    
    return;
}

sub remote_connect_error {
    my ( $self, $server, $con ) = @_;
    
    $self->_log( v => 4, msg => "error" );
    
    if ( my $fused = $con->fused ) {
        my $r = HTTP::Response->new( 503 );
        $r->content_type( 'text/plain' );
        $r->content( "Connection Error" );
        $r->header( 'content-length' => 16 );
        $fused->send( $r )
            unless ( $con->{_oneoff} );
        $fused->close();
    }

    $con->close();
       
    return;
}

sub remote_connect_timeout {
    my ( $self, $server, $con ) = @_;
    
    $self->_log( v => 4, msg => "timeout" );
    
    if ( my $fused = $con->fused ) {
        my $r = HTTP::Response->new( 504 );
        $r->content_type( 'text/plain' );
        $r->content( "Connection Timeout" );
        $r->header( 'content-length' => 18 );
        $fused->send( $r )
            unless ( $con->{_oneoff} );
        $fused->close();
    }

    $con->close();
       
    return;
}

1;
