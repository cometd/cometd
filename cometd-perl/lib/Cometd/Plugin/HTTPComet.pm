package Cometd::Plugin::HTTPComet;

use Cometd qw( Plugin::HTTP );
use base 'Cometd::Plugin::HTTP';

#use Cometd::Service::HTTPD;

use POE qw( Filter::HTTPD );
use HTTP::Response;
use JSON;
use HTTP::Date;
use Data::UUID;
use Time::HiRes qw( time );
use Digest::SHA1 qw( sha1_hex );
use bytes;

use Data::Dumper;

use strict;
use warnings;


sub new {
    my $class = shift;
    
    my $self = $class->SUPER::new(
        name => 'HTTPComet',
        uuid => Data::UUID->new(),
        hash_key => 's34l4b2021',
        protocol_version => .2,
        minimum_protocol_version => .1,
#        service => Cometd::Service::HTTPD->new(),
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

    $con->{_need_events} = 0;

    $self->start_http_request( @_ ) or return 1;
    
    # XXX
    # message=%5B%7B%22version%22%3A0.1%2C%22minimumVersion%22%3A0.1%2C%22channel%22%3A%22/meta/handshake%22%7D%5D&jsonp=dojo.io.ScriptSrcTransport._state.id1.jsonpCall
        
    $con->{_uri} ||= $req->uri;
    $con->{_r} = HTTP::Response->new( 200 )
        unless ( $con->{_r} );

    my $r = $con->{_r};

    my ( $params, %ops );
    my $ct = $req->content_type;
    if ( $ct eq 'text/javascript+json' ) {
        # POST
        if ( my $content = $req->content ) {
            $ops{message} = $content;
        }
    } elsif ( $ct eq 'application/x-www-form-urlencoded' ) {
        # POST or GET
        if ( my $content = $req->content ) {
            $params = $content;
        } elsif ( $con->{_params} ) {
            $params = $con->{_params};
        } elsif ( $req->uri =~ m!\?message=! ) {
            $params = ( $req->uri =~ m/\?(.*)/ )[ 0 ];
        }
    }
    
    if ( $params && $params =~ m/=/ ) {
        # uri param ?message=[json]
        %ops = map {
            my ( $k, $v ) = split( '=' );
            # imported from Cometd::Common
            uri_unescape( $k ) => uri_unescape( $v )
        } split( '&', $params );
    } else {
        # uri param ?[json]
        $ops{message} = $params;
    }
    
    unless ( $ops{message} ) {
        $r->code( 200 );
        $self->finish( $con, [ { successful => 'false', error => 'incorrect bayeux format' } ] );
        return 1;
    }

    my $objs = eval { jsonToObj( $ops{message} ) };
    if ( $@ ) {
        $self->finish( $con, [ { successful => 'false', error => "$@" } ] );
        return 1;
    }

    unless ( ref $objs eq 'ARRAY' ) {
        $self->finish( $con, [ { successful => 'false', error => 'incorrect bayeux format' } ] );
        return 1;
    }
    
    $server->_log(v => 4, msg => "ops/obj:".Data::Dumper->Dump([\%ops, $objs]));
    
    my $clid;
    $con->{_events} = [];
    my @errors;

    foreach my $obj ( @$objs ) {        
        unless ( ref $obj eq 'HASH' ) {
            $self->finish( $con, [ { successful => 'false', error => 'incorrect bayeux format' } ] );
            return 1;
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

        push( @errors, 'nice try. keep your clientId consistent - buddy' )
            if ( $clid && $clid ne $obj->{clientId} );

        push( @errors, 'clientId not specified' )
            unless ( $obj->{clientId} );
    
        last if ( @errors );

        
        if ( $obj->{channel} eq '/meta/handshake' ) {

            push(@{$con->{_events}}, {
                channel        => "/meta/handshake",
                version        => $self->{protocol_version},
                minimumVersion => $self->{minimum_protocol_version},
                supportedConnectionTypes => [ 'http-polling' ],
                clientId       => $clid,
                # XXX not in spec
                connectionId => '/meta/connections/'.$con->ID,
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

        } elsif ( $obj->{channel} eq '/meta/connect' ) {
        
            push(@{$con->{_events}}, {
                channel      => '/meta/connect',
                clientId     => $clid,
                #error        => '',
                successful   => $obj->{authToken} ? 'true' : 'false',
                connectionId => '/meta/connections/'.$con->ID,
                timestamp    => time2str( time() ),
                authToken    => sha1_hex( $clid.'|'.$self->{hash_key} ),
            });

#            $con->add_channels( [ '/sixapart/atom', '/chat' ] );
#            $con->send_event( channel => '/chat', data => "joined" );

        } elsif ( $obj->{channel} eq '/meta/reconnect' ) {

            push(@{$con->{_events}}, {
                channel      => '/meta/reconnect',
                successful   => 'true',
                clientId     => $obj->{clientId},
                authToken    => "auth token",
            });

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
        $self->finish( $con, {
            channel      => '/meta/reconnect',
            successful   => 'false',
            error        => join( ', ', @errors )
        });
        return 1;
    }
        

    if ( @{$con->{_events}} ) {
        $con->{_need_events} = 1;
    } else {
        $self->finish( $con, [ { successful => 'false', error => 'incorrect bayeux format' } ] );
    }

    return 1;

    #$con->add_channels( $channels );
    #$con->send_event( channel => $con->{chan}, data => "joined" );
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

    my $clid = $con->clid();

    $con->{_events} = [ map {
        $_->{event}->{clientId} eq $clid
        ? () : jsonToObj( $_->{event} )
    } @{$con->{_events}}, @$events ];

    $server->_log( v=>4, msg => Data::Dumper->Dump([ $events ]) );

    $self->finish( $con, delete $con->{_events} );
}


sub finish {
    my $self = shift;
    my ( $con, $out ) = @_;

    my $r = $con->{_r};
    $r->header( Expires => '-1' );
    $r->header( Pragma => 'no-cache' );
    $r->header( 'Cache-Control' => 'no-cache' );
    
    $out = eval { objToJson( [ $out ] ) };
    $out = objToJson( [ { successful => 'false', error => "$@" } ] )
        if ( $@ );
    
    $self->SUPER::finish( splice( @_, 1, 1, $out ) );

    unless ( $con->{_close} ) {
        # release control to other plugins
        if ( my $ff = delete $con->{_forwarded_from} ) {
            $con->plugin( $ff );
            return;
        }
        $self->release_connection( $con );
    }

    return;
}


1;
