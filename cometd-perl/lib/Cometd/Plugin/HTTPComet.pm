package Cometd::Plugin::HTTPComet;

use Cometd::Plugin;
use base 'Cometd::Plugin';

#use Cometd::Service::HTTPD;

use POE qw( Filter::HTTPD );
use HTTP::Response;
use URI::Escape;
use JSON;
use HTTP::Date;
use Data::UUID;
use Time::HiRes qw( time );
use bytes;

use Data::Dumper;

use strict;
use warnings;

sub new {
    my $class = shift;
    
    my $self = $class->SUPER::new(
        name => 'HTTPComet',
        uuid => Data::UUID->new(),
#        service => Cometd::Service::HTTPD->new(),
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
    return 0;
    $self->take_connection( $con );
    # POE::Filter::Stackable object:
    $con->filter->push( POE::Filter::HTTPD->new() );
    $con->filter->shift(); # pull off Filter::Stream
    return 1;
}

sub local_receive {
    my ( $self, $server, $con, $req ) = @_;

    my $r;
    $con->{_need_events} = 0;

    SWITCH: {    

    if ( ref $req && $req->isa( 'HTTP::Response' ) ) {
        $r = $con->{_r} || $req; # a prebuilt response
        $con->{_close} = 1; # default anyway
    } elsif ( ref $req && $req->isa( 'HTTP::Request' ) ) {
        if ( !defined( $con->{_close} ) ) {
            my $connection = $req->header( 'connection' );
            $con->{_close} = 0 if ( $connection && $connection =~ m/^keep-alive$/i );
        }
    
        $con->{_uri} ||= $req->uri;

#        open(FH,">>debug.txt");
#        print FH "connection: ".$con->ID."\n";

        warn Data::Dumper->Dump([$req])."\n";
    
#        print FH Data::Dumper->Dump([$req])."\n";
#        print FH "-------\n";

   
        $r = $con->{_r} || HTTP::Response->new();
        $r->header( Date => time2str( time() ) ) unless ( $r->header( 'Date' ) );
        $r->header( Server => 'Cometd (http://cometd.com/)' );
        # no cache
        $r->header( Expires => time2str( 0 ) );
        $r->header( Pragma => 'no-cache' );
        $r->header( 'Cache-Control' => 'no-cache' );
    
#        print FH Data::Dumper->Dump([$r])."\n";
#        close(FH);
    
        # /cometd?message=%5B%7B%22version%22%3A0.1%2C%22minimumVersion%22%3A0.1%2C%22channel%22%3A%22/meta/handshake%22%7D%5D&jsonp=dojo.io.ScriptSrcTransport._state.id1.jsonpCall
        $server->_log(v => 4, msg => Data::Dumper->Dump([$req]));
    
        my $params = ( $req->uri =~ m!\?message=! )
            ? ( $req->uri =~ m/\?(.*)/ )[ 0 ] : $req->content();
            
        my %ops;
        %ops = map {
            my ( $k, $v ) = split( '=' );
            $server->_log(v => 4, msg => "$k:$v" );
        
            $k => URI::Escape::uri_unescape( $v )
        
        } split( '&', $params )
            if ( $params );
        
        my $out = '';
        
        unless ( $ops{message} ) {
            $r->code( 200 );
            my $out = objToJson( { error => "incorrect bayeux format" } );
            $r->content( $out );
            $r->header( 'Content-Length' => length( $out ) );
            last SWITCH;
        }

#            $self->{service}->handle_request_json( $con, $req, $ops{message} );
        my $obj = eval { jsonToObj( $ops{message} ) };
        if ( $@ ) {
            $out = objToJson( [ { error => "$@" } ] );
            $r->content( $out );
            $r->header( 'Content-Length' => length( $out ) );
            last SWITCH;
        }
        $server->_log(v => 4, msg => "ops/obj:".Data::Dumper->Dump([\%ops, $obj]));

        if ( ref $obj eq 'ARRAY' ) {
              $obj = shift @$obj;
        }

        unless ( ref $obj eq 'HASH' ) {
            $r->code( 200 );
            my $out = objToJson( { error => "incorrect bayeux format" } );
            $r->content( $out );
            $r->header( 'Content-Length' => length( $out ) );
            last SWITCH;
        }

        
        if ( $obj->{channel} eq '/meta/handshake' ) {
            my $clid = $self->{uuid}->create_str();
            $r->header( 'X-Client-ID' => $clid );
            $out = {
                channel        => "/meta/handshake",
                version        => 0.1,
                minimumVersion => 0.1,
                supportedConnectionTypes => [ 'http-polling' ],
                clientId       => $clid,
                authSuccessful => 'true',
                advice => {
                    reconnect  => "retry", # one of "none", "retry", "handshake", "recover"
                    transport  => {
                        iframe => { },
                        flash  => { },
                        "http-polling" => {
                            interval => 5000, # reconnect delay in ms
                        },
                    },
                },
            };
        } elsif ( $obj->{channel} eq '/meta/connect' ) {
            my $clid = $con->clid( $obj->{clientId} ); # this contacts event manager
            $con->{_need_events} = 1;
            $con->{_events} = [{
                channel      => '/meta/connect',
                clientId     => $clid,
                error        => '',
                successful   => 'true',
                connectionId => '/meta/connections/'.$con->ID,
                timestamp    => $r->header( 'Date' ),
                authToken    => 'auth token',
            }];
            $con->add_channels( [ '/sixapart/atom', '/chat' ] );
            $con->send_event( channel => '/chat', data => "joined" );
            return 1;
        } elsif ( $obj->{channel} eq '/meta/reconnect' ) {
            my $clid = $con->clid( $obj->{clientId} ); # this contacts event manager
            $con->{_need_events} = 1;
#            $con->{_events} = [{
#                channel      => '/meta/reconnect',
#                successful   => 'true',
#                clientId     => $obj->{clientId},
#                authToken    => "auth token",
#            }];
            return 1;
        }
        
        $out = eval { objToJson( [ $out ] ) };
        if ( $@ ) {
            $out = objToJson( [ { error => "$@" } ] );
        }
        $r->content( $out );
        $r->header( 'Content-Length' => length( $out ) );
        last SWITCH;
        
        #$con->add_channels( $channels );
        #$con->send_event( channel => $con->{chan}, data => "joined" );
        
    }

    }; # SWITCH
    
#   $close = 1 if ( $con->{__requests} && $con->{__requests} > 100 );

    $self->finish( $con );   

    return 1;    
}

sub events_ready {
    my ( $self, $server, $con, $cid ) = @_;
    if ( $con->{_need_events} ) {
        $con->{_need_events} = 0;
        $con->get_events();
    }
}

sub events_received {
    my ( $self, $server, $con, $events ) = @_;
    $con->{_events} = []
        unless ( $con->{_events} );

    my $newlist = [];
    foreach ( @$events ) {
        push( @$newlist, jsonToObj( $_->{event} ) );
    }    

    push( @{$con->{_events}}, @$newlist );

    $server->_log( v=>4, msg => Data::Dumper->Dump([ $events ]) );

    my $r = $con->{_r};
    my $out = eval { objToJson( delete $con->{_events} ) };
    if ( $@ ) {
        $out = objToJson( [ { error => "$@" } ] );
    }
    $r->content( $out );
    $r->header( 'Content-Length' => length( $out ) );
    
    $self->finish( $con );
}


sub finish {
    my ( $self, $con ) = @_;

    my $r = $con->{_r};
    if ( $con->{_close} ) {
        $r->header( 'connection' => 'close' );
        $con->wheel->pause_input(); # no more requests
        $con->send( $r );
        $con->close();
    } else {
        # TODO set timeout
        $r->header( 'connection' => 'keep-alive' );
        $con->send( $r );
        $con->{__requests}++;
        $con->wheel->resume_input();
        # release control to other plugins
        $self->release_connection( $con );
    }
}

1;
