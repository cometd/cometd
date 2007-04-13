package Sprocket::Plugin::HTTP::Comet;

use Sprocket qw( Plugin::HTTP );
use base 'Sprocket::Plugin::HTTP';

#use Sprocket::Service::HTTPD;

use POE qw( Filter::HTTPD );
use Cometd::Connection;
use HTTP::Response;
use JSON;
use HTTP::Date;
use Data::UUID;
use Time::HiRes qw( time );
use Digest::SHA1 qw( sha1_hex );
use Scalar::Util qw( blessed );
use bytes;

use Data::Dumper;

use strict;
use warnings;

sub new {
    my $class = shift;
    
    my $self = $class->SUPER::new(
        name => 'HTTP::Comet',
        uuid => Data::UUID->new(),
        json => JSON->new(),
        hash_key => 's34l4b2021',
        protocol_version => .2,
        minimum_protocol_version => .1,
#        service => Sprocket::Service::HTTPD->new(),
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

    $con->{_need_events} = 0;
    
    $self->start_http_request( @_ ) or return OK;
    
    $con = bless( $con, 'Cometd::Connection' );
    
    my $r = $con->{_r} ||= HTTP::Response->new( 200 );
    
    # XXX
    # message=%5B%7B%22version%22%3A0.1%2C%22minimumVersion%22%3A0.1%2C%22channel%22%3A%22/meta/handshake%22%7D%5D&jsonp=dojo.io.ScriptSrcTransport._state.id1.jsonpCall
        
    $con->{_uri} ||= $req->uri;

    my ( $params, %ops );
    my $ct = $req->content_type;
    
    if ( $ct && $ct eq 'text/javascript+json' ) {
        # POST
        if ( my $content = $req->content ) {
            $ops{message} = $content;
        }
    } elsif ( $ct && $ct eq 'application/x-www-form-urlencoded' ) {
        # POST or GET
        if ( my $content = $req->content ) {
            $params = $content;
        } elsif ( $con->{_params} ) {
            $params = $con->{_params};
        } elsif ( $req->uri =~ m!\?message=! ) {
            $params = ( $req->uri =~ m/\?(.*)/ )[ 0 ];
        }
    }
    
    if ( $params && $params =~ m/\=/ ) {
        # uri param ?message=[json]
        %ops = map {
            my ( $k, $v ) = split( '=' );
            # imported from Sprocket::Common
            uri_unescape( $k ) => uri_unescape( $v )
        } split( '&', $params );
    } else {
        # uri param ?[json]
        $ops{message} = $params;
    }
    
    unless ( $ops{message} ) {
        $r->code( 200 );
        $con->call( finish => [ { successful => 'false', error => 'incorrect bayeux format' } ] );
        return OK;
    }

    my $objs = eval { $self->{json}->jsonToObj( $ops{message} ) };
    if ( $@ ) {
        $con->call( finish =>  [ { successful => 'false', error => "$@" } ] );
        return OK;
    }

    unless ( ref $objs eq 'ARRAY' ) {
        $con->call( finish => [ { successful => 'false', error => 'incorrect bayeux format' } ] );
        return OK;
    }
    
#    $server->_log(v => 4, msg => "ops/obj:".Data::Dumper->Dump([\%ops, $objs]));
    
    my $clid;
    $con->{_events} = [];
    my @errors;

    foreach my $obj ( @$objs ) {        
        unless ( ref $obj eq 'HASH' ) {
            $con->call( finish => [ { successful => 'false', error => 'incorrect bayeux format' } ] );
            return OK;
        }
        
        # TODO protocol version
        unless ( $clid ) {
            # specified clientId and authToken
            if ( $obj->{clientId} && $obj->{authToken}
                && sha1_hex( $obj->{clientId}.'|'.$self->{hash_key} ) eq $obj->{authToken} ) {
                # auth ok
                $clid = $con->clid( $obj->{clientId} ); # this contacts event manager
            } elsif ( $obj->{clientId} && $obj->{authToken} ) {
                push( @errors, 'invalid authToken' );
            } elsif ( $obj->{clientId} && !$obj->{authToken} ) {
                push( @errors, 'clientId cannot be specified without an authToken' );
            } elsif ( $obj->{authToken} ) {
                push( @errors, 'invalid authToken, no clientId to match it to' );
            } elsif ( $obj->{channel} eq '/meta/handshake' ) {
                $clid = $self->{uuid}->create_str();
            }
        }

        if ( $obj->{channel} ne '/meta/handshake' ) {
            push( @errors, 'nice try. keep your clientId consistent - buddy' )
                if ( $clid && $clid ne $obj->{clientId} );

            push( @errors, 'clientId not specified' )
                unless ( $obj->{clientId} );
        }

        last if ( @errors );

        
        if ( $obj->{channel} eq '/meta/handshake' ) {

            push(@{$con->{_events}}, {
                channel        => "/meta/handshake",
                version        => $self->{protocol_version},
                minimumVersion => $self->{minimum_protocol_version},
                supportedConnectionTypes => [ 'http-polling' ],
                clientId       => $clid,
                # XXX not in spec
                connectionId   => '/meta/connections/'.$con->ID,
                authToken      => sha1_hex( $clid.'|'.$self->{hash_key} ),
                authSuccessful => $obj->{authToken} ? 'true' : 'false',
                advice => {
                    reconnect  => 'retry', # one of "none", "retry", "handshake", "recover"
                    transport  => {
                        iframe => { },
                        flash  => { },
                        'http-polling' => {
                            interval => 5000, # reconnect delay in ms
                        },
                    },
                },
            });
    
            # XXX can a handshake block the client?
            $con->call( finish => delete $con->{_events} );
            
            return OK;

        } elsif ( $obj->{channel} eq '/meta/connect' ) {
        
            push(@{$con->{_events}}, {
                channel      => '/meta/connect',
                clientId     => $clid,
                #error        => '',
                successful   => 'true',
                connectionId => '/meta/connections/'.$con->ID,
                timestamp    => time2str( time() ),
                authToken    => sha1_hex( $clid.'|'.$self->{hash_key} ),
            });

            $con->add_channels( [ '/sixapart/atom', '/chat' ] );
            $con->get_events();
#            $con->send_event( channel => '/chat', data => "joined" );

        } elsif ( $obj->{channel} eq '/meta/reconnect' ) {

            push(@{$con->{_events}}, {
                channel      => '/meta/reconnect',
                clientId     => $clid,
                successful   => 'true',
                connectionId => '/meta/connections/'.$con->ID,
                timestamp    => time2str( time() ),
                authToken    => sha1_hex( $clid.'|'.$self->{hash_key} ),
            });
            $con->add_channels( [ '/sixapart/atom', '/chat' ] );
            $con->get_events();

        } elsif( $obj->{channel} =~ m!^/meta/! ) {
            # XXX 
        } else {
            $con->{_need_events} = 1;
#            $con->add_channels( [ '/sixapart/atom', '/chat' ] );
#            $con->send_event( channel => '/chat', data => "joined" );
            $con->send_event( @{$obj}{qw( channel data clientId )} );
        }
    }
        

    if ( @errors ) {
        $con->call( finish => [ {
            channel      => '/meta/reconnect',
            successful   => 'false',
            error        => join( ', ', @errors )
        } ]);
        return OK;
    }
        

    if ( @{$con->{_events}} ) {
        $con->{_need_events} = 1;
    } else {
        $con->call( finish => [ { successful => 'false', error => 'incorrect bayeux format' } ] );
    }

    return OK;

    #$con->add_channels( $channels );
    #$con->send_event( channel => $con->{chan}, data => "joined" );
}

sub local_disconnected {
    my ( $self, $server, $con ) = @_;
    
    $server->_log( v => 4, msg => "DISCONNECTED: $con" );
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

#    $con->{_events} = []
#        unless ( $con->{_events} );
    return unless ( @$events );

    my $clid = $con->clid();

#    warn Data::Dumper->Dump([ $events ]);

    if ( $events && @$events ) {
        foreach ( @$events ) {
            push( @{$con->{_events}}, $self->{json}->jsonToObj( $_->{event} ) );
        }
    }
    
#    $con->{_events} = [ map {
#        $_->{event}->{clientId} eq $clid
#        ? () : ( blessed( $_ ) ? $_->as_string : $self->{json}->jsonToObj( $_->{event} ) )
#    } @{$con->{_events} ];

#    $server->_log(v => 4, msg => Data::Dumper->Dump([ $events ]) );

    $con->call( finish => delete $con->{_events} );
}


sub finish {
    my $self = shift;
    my ( $server, $con, $out ) = @_;

    my $r = $con->{_r};
    $r->header( Expires => '-1' );
    $r->header( Pragma => 'no-cache' );
    $r->header( 'Cache-Control' => 'no-cache' );
    
    $out = eval { $self->{json}->objToJson( $out ) };
    $out = $self->{json}->objToJson( [ { successful => 'false', error => "$@" } ] )
        if ( $@ );
    
    splice( @_, 2, 1, $out );
    $self->SUPER::finish( @_ );

    unless ( $con->{_close} ) {
        # release control to other plugins
        if ( my $ff = delete $con->{_forwarded_from} ) {
            $con->plugin( $ff );
            return OK;
        }
        $self->release_connection( $con );
        $con = bless( $con, 'Sprocket::Connection' );
    }

    return OK;
}


1;
