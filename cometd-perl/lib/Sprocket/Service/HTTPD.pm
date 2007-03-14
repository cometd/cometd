package Sprocket::Service::HTTPD;

use strict;
use Scalar::Util qw( weaken );
use URI::Escape;
use JSON;
use Carp qw( croak );

our @socket_list;

our %supported_tunnels = (
    'long-polling' => 1,
    'iframe' => 0,
    'flash' => 0,
);

# also valid actions
our %actions = (
    handshake => 'handshake',
    connect => 'connect',
    reconnect => 'connect',
    subscribe => 'subscribe',
    unsubscribe => 'subscribe',
    disconnect => 'disconnect',
    deliver => 'deliver',
);

sub import {
    my ($class, $args) = @_;
    my $package = caller();

    croak 'Sprocket requires args as a hash ref'
        if ($args && ref($args) ne 'HASH');

    # XXX may not need to export the top 3, test this
    my %exports = qw(
        socket_list    \@socket_list
    );

    {
        no strict 'refs';
        foreach (keys %exports) {
            *{ $package . '::' . $_ } = eval "$exports{$_}";
        }
    }

    my @unknown = sort keys %$args;
    croak "Unknown $class import arguments: @unknown" if @unknown;
}

sub new {
    my $class = shift;
    bless( { @_ }, ref $class || $class );
}

sub parse_event {
    my ( $client, $op, $msg ) = @_;
    my $event = {};
    if ( $msg !~ m/^\[/ ) {
        $client->_simple_response(500, 'Invalid data');
        return 1;
    }
    
    my $o = eval { jsonToObj( $msg ); };
    if ( $@ ) {
        warn $@;
        $client->_simple_response(500, 'Invalid data');
        return 1;
    }
    
    # double check
    if ( ref($o) ne 'ARRAY' ) {
        $client->_simple_response(500, 'Invalid data');
        return 1;
    }
    
    # TODO handle multiple messages
    foreach my $m ( @$o ) {
        next unless ( ref( $m ) eq 'HASH' );
        if ( $m->{channel} ) {
            if ( my ($act) = ( $m->{channel} =~ m!/meta/([^/]+)! ) ) {
                if ( $act eq 'handshake' || $act eq 'subscribe'
                || $act eq 'unsubscribe' || $act eq 'connect'
                || $act eq 'reconnect' ) {
                    $op->{action} = $act;
                    $event = $m;
                } else {
                    warn "invalid action $act";
                }
                last;
            } else {
                $op->{action} = 'deliver';
                $event = $m;
                last;
            }
        }
    }
    
    return $event;
}

sub handle_request_reproxy {
    my ($self, $client, $hd) = @_;
    
    my $opts;
    unless ( $opts = $hd->header('x-cometd') ) {
        warn "no x-cometd header sent";
        $client->_simple_response(404, 'No x-cometd header sent');
        return 1;
    }

    my $op = { map {
        my ( $k, $v ) = split( /=/ );
        unless( $v ) { $v = ''; }
        lc($k) => $v
    } split( /;\s*/, $opts ) };

    #require Data::Dumper;
    #warn Data::Dumper->Dump([$hd]);
   
    my $event;
    # XXX need to verify objects against a template
    if ( my $msg = $hd->header('x-cometd-data') ) {
        $msg = uri_unescape( $msg );
        warn "X-COMETD-DATA: $msg\n";
        $event = $self->parse_event( $client, $op, $msg );
        return 1 unless ($event);
    }
    
    if ( !$event ) {
        $client->_simple_response(500, 'Invalid data');
        return 1;
    }
   
    unless ( $op->{id} ) {
        warn "404 no client id set";
        $client->_simple_response(500, 'No client id returned from backend');
        return 1;
    }
    return $self->handle_request( $client, $hd, $op, $event );
}

sub handle_request_json {
    my ($self, $client, $req, $json) = @_;

    my $close = 1;
    my $op = {
        # TODO?
    };

    my $r;
    if ( $json && $json =~ m/^\[/ ) {
        my $event = eval { jsonToObj( $json ) };
        if ( $@ ) {
            warn "error parsing json: $@";
        } else {
            return $self->handle_request( $client, $req, $op, $event );
        }
    }
    $r = HTTP::Response->new( 500 );
    $r->content( 'Server Error - Incorrect JSON format' );
    
    $r = HTTP::Response->new( 200 )
        unless($r);
    $r->content_type( 'text/plain' )
        unless($r->content_type);
    $r->content( 'cometd test server' )
        unless($r->content);

    my $connection = $req->header( 'connection' );
    $close = 0 if ( $connection && $connection =~ m/^keep-alive$/i );
    
    $client->write( $r );
    
    $client->close() unless ( $close );

    return 1;
}

sub handle_request {
    my ($self, $client, $hd, $op, $event) = @_;
    
    # pull action, domain and id from backend request
    $op->{action} ||= 'handshake';
    $op->{tunnelType} ||= 'long-polling';
    $op->{cid} ||= '/meta/connections/' . $op->{id};
    $op->{eid} ||= 0;

    if ( $op->{channels} ) {
        $op->{channels} = { map { $_ => 1 } split( /;\s?/, $op->{channels} ) };
    } else {
        $op->{channels} = {};
    }
    
    warn "action: $op->{action}";

    if ( !$actions{ $op->{action} } ) {
        warn "invalid action $op->{action}";
        $client->_simple_response(500, 'Invalid action');
        return 1;
    }

    # dispatch to the action
    no strict 'refs';
    return "action_$actions{$op->{action}}"->( $self, $client, $hd, $op, $event );
}


sub action_handshake {
    my ($self, $client, $hd, $op, $event) = @_;

    # $event = [{"version":0.1,"minimumVersion":0.1,"channel":"/meta/handshake"}]
    
    my $res = $self->new_response( $client, 200 );
    $res->header( 'Content-Type', 'text/html' );

    $res->header( 'Set-Cookie', $hd->header( 'set-cookie' ) )
        if ( $hd->header( 'set-cookie' ) );

    if ( $res->can( "to_string_ref" ) ) {
        $client->write( $res->to_string_ref );
    } else {
        $client->write( $res );
    }
    $client->tcp_cork( 1 );
    $client->watch_write( 0 ) if $client->write( objToJson({
        channel => '/meta/handshake',
        version => 0.1,
        minimumVersion => 0.1,
        supportedConnectionTypes => [
            grep { $supported_tunnels{$_} } keys %supported_tunnels
        ],
        clientId => $op->{id},
        authSuccessful => 'true',
        #authToken => "SOME_NONCE_THAT_NEEDS_TO_BE_PROVIDED_SUBSEQUENTLY",
    }) );
    
    my $connection = $hd->header( 'connection' );
    my $close = ( $connection && $connection =~ m/^keep-alive$/i ) ? 0 : 1;
    
    $client->write( $res );
    $client->close() unless ( $close );

    warn "sent handshake to $op->{id}";
    return 1;
}

sub action_subscribe {
    my ($self, $client, $hd, $op, $event) = @_;
    
    # XXX temporary, may ask the event server to reply instead
    
    # [{"channel":"/meta/subscribe","subscription":"/magnets/moveStart","connectionId":null,"clientId":"id"}]
    my $ev = {};
    # TODO arrays of valid keys for each type
    @{$ev}{qw( channel subscription connectionId clientId authToken )} =
        delete @{$event}{qw( channel subscription connectionId clientId authToken )};
    
    multiplex_send($ev);
    
    # TODO have the event server do this response
    $ev->{successful} = 'true';
    
    my $res = $client->{res_headers} = $self->new_response( 200 );
    $res->header( 'Content-Type', 'text/html' );
    #$res->header( 'Connection', 'close' );

    $res->header( 'Set-Cookie', $hd->header( 'set-cookie' ) )
        if ( $hd->header( 'set-cookie' ) );
 
    if ( $res->can( "to_string_ref" ) ) {
        $client->write( $res->to_string_ref );
    } else {
        $client->write( $res );
    }
    $client->tcp_cork( 1 );
    $client->watch_write( 0 ) if $client->write( objToJson($ev) );
    $client->close();
    
    warn "sent $op->{action} to $op->{id}";
    return 1;
}

sub action_deliver {
    my ($self, $client, $hd, $op, $event) = @_;
    
    # XXX verify tunnel
    @{$client->{scratch}}{qw( id cid eid tunnel ch )} = @{$op}{qw( id cid eid tunnelType channels )};
    
    warn "-------DELIVER------";

    # id to client obj map
    # TODO add_client sub
    $self->{ids}->{ $op->{id} } = $client;
    weaken( $self->{ids}->{ $op->{id} } );
    @{$self->{clients}} = grep { defined } ( @{$self->{clients}}, $client );
    foreach (@socket_list) { weaken( $_ ); }
    
    
    # XXX temporary, may ask the event server to reply instead
    
    # [{"channel":"/meta/subscribe","subscription":"/magnets/moveStart","connectionId":null,"clientId":"id"}]
    my $ev = { };
    # TODO arrays of valid keys for each type
    @{$ev}{qw( channel data connectionId clientId authToken )} =
        delete @{$event}{qw( channel data connectionId clientId authToken )};
    
    # this will give us the clients channels in an internal event
    multiplex_send({
        channel => '/meta/connect',
        clientId => $ev->{clientId},
    });
   
    multiplex_send($ev);
    
    my $res = $client->{res_headers} = $self->new_response( 200 );
    $res->header( 'Content-Type', 'text/html' );

    $res->header( 'Set-Cookie', $hd->header( 'set-cookie' ) )
        if ( $hd->header( 'set-cookie' ) );
 
    if ( $res->can( "to_string_ref" ) ) {
        $client->watch_write( 0 )
            if $client->write( $res->to_string_ref );
    } else {
        $client->watch_write( 0 )
            if $client->write( $res );
    }
    $client->tcp_cork( 1 );

    # XXX perhaps the client should deceide if this is a long-polling event delivery post
    # the client could have another connection
    
    warn "PUTTING CLIENT ON HOLD UNTIL EVENT: $op->{id}";
    return 1;
}

sub action_connect {
    my ($self, $client, $hd, $op, $event) = @_;

    unless ( $op->{id} && $op->{domain} ) {
        warn "no client id and/or domain set";
        $client->_simple_response(404, 'No client id and/or domain returned from backend');
        return 1;
    }

    my $res = $client->{res_headers} = $self->new_response( 200 );
    $res->header( 'Content-Type', 'text/html' );
    #$res->header( 'Connection', 'close' );

    $res->header( 'Set-Cookie', $hd->header( 'set-cookie' ) )
        if ( $hd->header( 'set-cookie' ) );
    
    if ( !$supported_tunnels{ $op->{tunnelType} } ) { 
        # not a valid tunnel type
        $client->watch_write( 0 ) if $client->write( objToJson(
            {
                channel => '/meta/'.$op->{action},
                successful => 'false',
                error => 'Tunnel type not supported',
                clientId => $op->{id},
                connectionId => $op->{cid},
            }
        ) );
        warn "invalid tunnel type, closing client";
        $client->close();
        return 1;
    }
    
    #$op{id} = $in->{id} if ( $in->{id} );
        
    @{$client->{scratch}}{qw( id cid eid ch tunnel )} = @{$op}{qw( id cid eid channels tunnelType )};

    # close already connected client if any
    if ( my $cli = $self->{ids}->{ $op->{id} } ) {
        # XXX check this
        if ( $cli->{scratch}{tunnel} && $cli->{scratch}{tunnel} eq 'iframe' ) {
            $cli->write( iframe_filter(
                '/meta/disconnect' => {
                    reason => 'Closing previous connection',
                }
            ) . ( ( $cli->{scratch}{tunnel} eq 'iframe' ) ? '</body></html>' : '' ) );
            multiplex_send({
                channel => '/meta/disconnect',
                clientId => $op->{id},
                data => {
                    channels => [ keys %{$cli->{scratch}{ch}} ],
                    previous => 1,
                },
            });
        }
        warn "closing old client $op->{id}";
        $cli->close();
    }
    
    # id to client obj map
    $self->{ids}->{ $op->{id} } = $client;
    weaken( $self->{ids}->{ $op->{id} } );
    @{$self->{clients}} = grep { defined } ( @{$self->{clients}}, $client );
    foreach (@socket_list) { weaken( $_ ); }

    multiplex_send({
        channel => '/meta/'.$op->{action},
        clientId => $op->{id},
        data => {
            channels => [ keys %{$client->{scratch}{ch}} ],
        },
    });

    if ( $op->{tunnelType} eq 'long-polling' ) {

        unless ( $op->{id} ) {
            warn "no client id set";
            $client->_simple_response(404, 'No client id returned from backend');
            return 1;
        }
    
        # $event = [{"version":0.1,"minimumVersion":0.1,"channel":"/meta/handshake"}]
        my $res = $client->{res_headers} = $self->new_response( 200 );
        $res->header( 'Content-Type', 'text/html' );
        #$res->header( 'Connection', 'close' );

        $res->header( 'Set-Cookie', $hd->header( 'set-cookie' ) )
            if ( $hd->header( 'set-cookie' ) );
 
        if ( $res->can( "to_string_ref" ) ) {
            $client->watch_write( 0 )
                if $client->write( $res->to_string_ref );
        } else {
            $client->watch_write( 0 )
                if $client->write( $res );
        }
        $client->tcp_cork( 1 );

        # XXX set timer or let client drop connection?
    
        warn "sent $op->{action} response for long-polling";

        return 1;
                
    } elsif ( $op->{tunnelType} eq 'iframe' ) {
        
        if ( $res->can( "to_string_ref" ) ) {
            $client->write( $res->to_string_ref );
        } else {
            $client->write( $res );
        }
        $client->tcp_cork( 1 );
    
        $op->{domain} =~ s/'/\\'/g;

        $client->write( qq~<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN"
	"http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">

<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en">
	<head>
		<title>COMETd: The Long Tail of Comet</title>
		<meta http-equiv="Content-Type" content="text/html; charset=utf-8"></meta>
		<script type="text/javascript">
			// window.parent.dojo.debug("tunnelInit");
			var noInit = false;
			var domain = "";
            var deliver = function() { };
			function init(){
				var sparams = document.location.search;
				if(sparams.length >= 0){
					if(sparams.charAt(0) == "?"){
						sparams = sparams.substring(1);
					}
					var ss = (sparams.indexOf("&amp;") >= 0) ? "&amp;" : "&";
					sparams = sparams.split(ss);
					for(var x=0; x<sparams.length; x++){
						var tp = sparams[x].split("=");
						if(typeof window[tp[0]] != "undefined"){
							window[tp[0]] = ((tp[1]=="true")||(tp[1]=="false")) ? eval(tp[1]) : tp[1];
						}
					}
				}

				if(noInit){ return; }
				/*
				if(domain.length > 0){
					document.domain = domain;
				}
				*/
				if(window.parent != window){
					/* Notify parent that we are loaded. */
					window.parent.cometd.tunnelInit(window.location, document.domain);
                    deliver = function(ch,msg) {
                        window.parent.cometd.deliver(msg);
                    };
				}
			}
		</script>
	</head>
	<body onload="try{ init(); }catch(e){ alert(e); }">
		<h4>COMETd: The Long Tail of Comet</h4>
	</body>
</html>~ );
 
        $client->watch_write( 0 ) if $client->write( iframe_filter(
            '/meta/'.$op->{action} => {
                successful => 'true',
                error => '',
                clientId => $op->{id},
                connectionId => $op->{cid},
                timestamp => time(), # XXX
                data => {
                    channels => [ keys %{$client->{scratch}{ch}} ],
                },
                ( $op->{eid} ? ( 'last' => $op->{eid} ) : () )
            }
        ) );
        
    }
}

sub new_response {
    my $self = shift;
    #Perlbal::HTTPHeaders->new_response(@_);
    HTTP::Response->new(@_);
}

sub multiplex_send {
    warn "TODO";
}

1;
