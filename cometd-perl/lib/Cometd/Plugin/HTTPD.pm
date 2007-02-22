package Cometd::Plugin::HTTPD;

use Cometd::Plugin;
use base 'Cometd::Plugin';

use Cometd::Service::HTTPD;

use POE qw( Filter::HTTPD );
use HTTP::Response;

use Data::Dumper;

use strict;
use warnings;

sub new {
    my $class = shift;
    
    my $self = $class->SUPER::new(
        name => 'HTTPD',
        service => Cometd::Service::HTTPD->new(),
        @_
    );

    return $self;
}

sub add_plugin {
    my $self = shift;
    
    return if ( $self->{_session_id} );
    
    # save the session id
    $self->{_session_id} =
    POE::Session->create(
        object_states =>  [
            $self => [qw(
                _start
                _stop
            )]
        ],
    )->ID();

    return undef;
}

sub as_string {
    __PACKAGE__;
}

sub _start {
    my $self = $_[OBJECT];
    $_[KERNEL]->alias_set( "$self" );
    $self->_log(v => 1, msg => 'started');
}

sub _stop {
    my $self = $_[OBJECT];
    $self->_log(v => 1, msg => 'stopped');
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

    my ( $out, $r );
    
#    open(FH,">>debug.txt");
#    print FH "connection: ".$con->ID."\n";
    my $close = 1;
    
    if ( $req->isa( 'HTTP::Response' ) ) {
        $r = $req; # a prebuilt response
        $close = 1; # default anyway
    } else {
#        $r = HTTP::Response->new( 200 );
#        $r->content_type( 'text/plain' );
#        $r->content( ( 'x' x 2048 ) );
#        $r->header( 'content-length' => 2048 );
        my $connection = $req->header( 'connection' );
        $close = 0 if ( $connection && $connection =~ m/^keep-alive$/i );
    }
    
#    print FH Data::Dumper->Dump([$req])."\n";
#    print FH "-------\n";
   
#   $close = 1 if ( $con->{__requests} && $con->{__requests} > 100 );
   
    if ( $r ) {
        if ( $close ) {
            $r->header( 'connection' => 'close' );
            $con->wheel->pause_input(); # no more requests
            $con->send( $r );
            $con->close();
        } else {
            # TODO set timeout
            $r->header( 'connection' => 'keep-alive' );
            $con->send( $r );
            $con->{__requests}++;
        }
        return;
    }
    
#    print FH Data::Dumper->Dump([$r])."\n";
#    close(FH);

    # /cometd?message=%5B%7B%22version%22%3A0.1%2C%22minimumVersion%22%3A0.1%2C%22channel%22%3A%22/meta/handshake%22%7D%5D&jsonp=dojo.io.ScriptSrcTransport._state.id1.jsonpCall
    $self->_log(v => 4, msg => Data::Dumper->Dump([$req]));
    
    if ( my $uri = $req->uri ) {
        my %ops = map {
            my ( $k, $v ) = split( '=' );
            $self->_log(v => 4, msg => "$k:$v" );
            
            $k => URI::Escape::uri_unescape( $v )
            
        } split( '&', ( $uri =~ m/\?(.*)/ )[ 0 ] );
        
        $self->_log(v => 4, msg => Data::Dumper->Dump([\%ops]));
        
        $self->{service}->handle_request_json( $con, $req, $ops{message} );
    } else {
        $self->{service}->handle_request_json( $con, $req, $req->content );
    }

    return 1;
}

1;
