package Cometd::Plugin::HTTP;

use Cometd::Plugin;
use base 'Cometd::Plugin';

use POE qw( Filter::HTTPD );
use HTTP::Response;

use Data::Dumper;

use strict;
use warnings;

sub new {
    my $class = shift;
    
    my $self = $class->SUPER::new(
        name => 'HTTP',
        @_
    );

    return $self;
}

sub add_plugin {
    my $self = shift;
    
    return if ( $self->{session_id} );
    
    # save the session id
    $self->{session_id} =
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
    } elsif ( $req->isa( 'HTTP::Request' ) ) {
        $r = HTTP::Response->new( 200 );
        $r->content_type( 'text/plain' );
        $r->content( ( 'x' x 2048 ) );
        $r->header( 'content-length' => 2048 );
        my $connection = $req->header( 'connection' );
        $close = 0 if ( $connection && $connection =~ m/^keep-alive$/i );
    } else {
        warn "request isn't an HTTP object";
        $r = HTTP::Response->new( 500 );
    }
    
#    print FH Data::Dumper->Dump([$req])."\n";
#    print FH "-------\n";
   
#   $close = 1 if ( $con->{__requests} && $con->{__requests} > 100 );
   
    if ( $close ) {
        $r->header( 'connection' => 'close' );
        $con->wheel->pause_input(); # no more requests
        $con->send( $r );
        $con->close();
    } else {
        # TODO set timeout based on keepalive: header
        $r->header( 'connection' => 'keep-alive' );
        $con->send( $r );
        $con->{__requests}++;
    }

    return 1;
}

    
1;
