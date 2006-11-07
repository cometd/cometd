package Cometd::Perlbal::Service;

use Cometd;

use Carp qw( croak );
use Scalar::Util qw( weaken );
use Cometd::Perlbal::Service::Connector;
use URI::Escape;
use JSON;

our ( @clients, %ids, $filter, $filter_package );

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

use constant KEEPALIVE_TIMER => 1;

sub import {
    my ($class, $args) = @_;
    my $package = caller();

    croak 'Cometd requires args as a hash ref'
        if ($args && ref($args) ne 'HASH');

    my %exports = qw(
        clients   \@clients
        ids         \%ids
        filter      \$filter
        load        \&load
        register    \&register
        unregister  \&unregister
        unload      \&unload
    );

    {
        no strict 'refs';
        foreach (keys %exports) {
            *{ $package . '::' . $_ } = eval "$exports{$_}";
        }
    }

    $filter_package = 'Cometd::' . ( delete $args->{filter} || 'Filter::JSON' );

    my @unknown = sort keys %$args;
    croak "Unknown $class import arguments: @unknown" if @unknown;

    eval "use $filter_package";
    croak $@ if ($@);
}

sub register {
    my $service = $_[ 1 ];
   
    Danga::Socket->AddTimer( KEEPALIVE_TIMER, \&time_keepalive );
 
    $service->register_hook( Cometd => start_proxy_request => \&start_proxy_request );
        
    Perlbal::Service::add_role(
        cometd => sub {
            Cometd::Perlbal::Service::Connector->new( @_ );
        }
    );

    return 1;
}

sub unregister {
    $service->uregister_hook( Cometd => 'start_proxy_request' );
    Perlbal::Service::remove_role( 'cometd' );
    
    return 1;
}

sub load {
    no strict 'refs';
    $filter = $filter_package->new();
    return 1;
}

sub unload {
    @clients = %ids = ();
    $filter = undef;
    return 1;
}

sub start_proxy_request {
    my Perlbal::ClientProxy $client = shift;
    
    my Perlbal::HTTPHeaders $head = $client->{req_headers};
    return 0 unless $head;
   
    # requires patched Perlbal, backend response headers
    my Perlbal::HTTPHeaders $hd = $client->{res_headers};
    unless ( $hd ) {
        # XXX no res_headers are available when cometd isn't reproxied
        warn "You are running an old version of Perlbal, please use check out the lastest build in svn at code.sixapart.com\n";
        return 0;
    }
    delete $client->{res_headers};

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
    return "action_$actions{$op->{action}}"->( $client, $hd, $op, $event );
}

sub action_handshake {
    my ($client, $hd, $op, $event) = @_;

    # $event = [{"version":0.1,"minimumVersion":0.1,"channel":"/meta/handshake"}]
    
    my $res = $client->{res_headers} = Perlbal::HTTPHeaders->new_response( 200 );
    $res->header( 'Content-Type', 'text/html' );

    $res->header( 'Set-Cookie', $hd->header( 'set-cookie' ) )
        if ( $hd->header( 'set-cookie' ) );

    $client->write( $res->to_string_ref );
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
    $client->close();

    warn "sent handshake to $op->{id}";
    return 1;
}

sub action_subscribe {
    my ($client, $hd, $op, $event) = @_;
    
    # XXX temporary, may ask the event server to reply instead
    
    # [{"channel":"/meta/subscribe","subscription":"/magnets/moveStart","connectionId":null,"clientId":"id"}]
    my $ev = {};
    # TODO arrays of valid keys for each type
    @{$ev}{qw( channel subscription connectionId clientId authToken )} =
        delete @{$event}{qw( channel subscription connectionId clientId authToken )};
    
    Cometd::Perlbal::Service::Connector::multiplex_send($ev);
    
    # TODO have the event server do this response
    $ev{successful} = 'true';
    
    my $res = $client->{res_headers} = Perlbal::HTTPHeaders->new_response( 200 );
    $res->header( 'Content-Type', 'text/html' );
    #$res->header( 'Connection', 'close' );

    $res->header( 'Set-Cookie', $hd->header( 'set-cookie' ) )
        if ( $hd->header( 'set-cookie' ) );
 
    $client->write( $res->to_string_ref );
    $client->tcp_cork( 1 );
    $client->watch_write( 0 ) if $client->write( objToJson($ev) );
    $client->close();
    
    warn "sent $op->{action} to $op->{id}";
    return 1;
}

sub action_deliver {
    my ($client, $hd, $op, $event) = @_;
    
    # XXX verify tunnel
    @{$client->{scratch}}{qw( id cid eid tunnel ch )} = @{$op}{qw( id cid eid tunnelType channels )};
    
    warn "-------DELIVER------";

    # id to client obj map
    # TODO add_client sub
    $ids{ $op->{id} } = $client;
    weaken( $ids{ $op->{id} } );
    @clients = grep { defined } ( @clients, $client );
    foreach (@socket_list) { weaken( $_ ); }
    
    
    # XXX temporary, may ask the event server to reply instead
    
    # [{"channel":"/meta/subscribe","subscription":"/magnets/moveStart","connectionId":null,"clientId":"id"}]
    my $ev = { };
    # TODO arrays of valid keys for each type
    @{$ev}{qw( channel data connectionId clientId authToken )} =
        delete @{$event}{qw( channel data connectionId clientId authToken )};
    
    # this will give us the clients channels in an internal event
    Cometd::Perlbal::Service::Connector::multiplex_send({
        channel => '/meta/connect',
        clientId => $ev->{clientId},
    });
    
    Cometd::Perlbal::Service::Connector::multiplex_send($ev);
    
    my $res = $client->{res_headers} = Perlbal::HTTPHeaders->new_response( 200 );
    $res->header( 'Content-Type', 'text/html' );

    $res->header( 'Set-Cookie', $hd->header( 'set-cookie' ) )
        if ( $hd->header( 'set-cookie' ) );
 
    $client->watch_write( 0 ) if $client->write( $res->to_string_ref );
    $client->tcp_cork( 1 );

    # XXX perhaps the client should deceide if this is a long-polling event delivery post
    # the client could have another connection
    
    warn "PUTTING CLIENT ON HOLD UNTIL EVENT: $op->{id}";
    return 1;
}

sub action_connect {
    my ($client, $hd, $op, $event) = @_;

    unless ( $op->{id} && $op->{domain} ) {
        warn "no client id and/or domain set";
        $client->_simple_response(404, 'No client id and/or domain returned from backend');
        return 1;
    }

    my $res = $client->{res_headers} = Perlbal::HTTPHeaders->new_response( 200 );
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
    if ( my $cli = $ids{ $op->{id} } ) {
        # XXX check this
        if ( $cli->{scratch}{tunnel} && $cli->{scratch}{tunnel} eq 'iframe' ) {
            $cli->write( iframe_filter(
                '/meta/disconnect' => {
                    reason => 'Closing previous connection',
                }
            ) . ( ( $cli->{scratch}{tunnel} eq 'iframe' ) ? '</body></html>' : '' ) );
            Cometd::Perlbal::Service::Connector::multiplex_send({
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
    $ids{ $op->{id} } = $client;
    weaken( $ids{ $op->{id} } );
    @clients = grep { defined } ( @clients, $client );
    foreach (@socket_list) { weaken( $_ ); }

    Cometd::Perlbal::Service::Connector::multiplex_send({
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
        my $res = $client->{res_headers} = Perlbal::HTTPHeaders->new_response( 200 );
        $res->header( 'Content-Type', 'text/html' );
        #$res->header( 'Connection', 'close' );

        $res->header( 'Set-Cookie', $hd->header( 'set-cookie' ) )
            if ( $hd->header( 'set-cookie' ) );
 
        $client->watch_write( 0 ) if $client->write( $res->to_string_ref );
        $client->tcp_cork( 1 );

        # XXX set timer or let client drop connection?
    
        warn "sent $op->{action} response for long-polling";

        return 1;
                
    } elsif ( $op->{tunnelType} eq 'iframe' ) {
        
        $client->write( $res->to_string_ref );
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


# called from Cometd::Perlbal::Service::Connector

sub handle_event {
    my $objs = shift;
    my $cleanup = 0;

    warn "handle event";

    $objs = [ $objs ] unless ( ref( $objs ) eq 'ARRAY' );
    
    my $time = time();

    foreach $o ( @$objs ) {
        next unless ( ref( $o ) eq 'HASH' );
        next if ( !$o->{channel} );
         
        $o->{timestamp} = $time; # XXX
        
        my $ijson = iframe_filter( $o->{channel}, $o );
        my $json = objToJson( $o )."\n";
        
        warn "event from backend: $json";
        
        if ( $o->{channel} =~ m~^/meta/clients/(.*)~ ) {
            if ( exists( $ids{ $1 } ) ) {
                my $l = $ids{ $1 };
                $l->watch_write(0) if $l->write( $l->{scratch}{tunnel} eq 'iframe' ? $ijson : $json );
                $l->{alive_time} = $time;
                next;
            }
        }

        my $id;
        my $action;
        if ( $o->{channel} =~ m~^/meta/(.*)~ ) {
            my $meta = $1;
            warn "meta channel: $meta";
            
            next if ( !$o->{clientId} );
           
            if ( $meta eq 'internal/reconnect' && $o->{channels}
                && ref($o->{channels}) eq 'ARRAY' ) {
                $action = 'add_chs';
                $id = $o->{clientId};
                warn "channel update for $id!";
            }
            
            if ( $meta eq 'subscribe' && $o->{subscription} ) {
                if ( $o->{successful} ne 'false' ) {
                    # add channel
                    $action = 'add_ch';
                }
                $id = $o->{clientId};
            }

            if ( $meta eq 'unsubscribe' && $o->{subscription} ) {
                if ( $o->{successful} ne 'false' ) {
                    # remove channel
                    $action = 'rem_ch';
                }
                $id = $o->{clientId};
            }

            if ( $meta eq 'disconnect' ) {
                $action = 'dis_cli';
                $id = $o->{clientId};
            }

            if ( $meta eq 'ping' || $meta eq 'handshake'
                || $meta eq 'connect' || $meta eq 'reconnect' ) {
                # XXX is reconnect sent back to client, or is connect used
                $id = $o->{clientId}; 
            }

            if ( $id ) {
                my $l = $ids{ $id };
                if ( !$l ) {
                    warn "client id: $id not in id list: ".join(',',keys %ids);
                    next;
                }
                
                unless ( $action eq 'add_chs' ) {
                    warn "out to $id: $json";
                    $l->watch_write(0) if $l->write( $l->{scratch}{tunnel} eq 'iframe' ? $ijson : $json );
                    $l->{alive_time} = $time;
                }

                if ( $action ) {
                    if ( $action eq 'add_ch' ) {
                        $l->{scratch}{ch}->{ $o->{subscription} } = 1;
                    } elsif ( $action eq 'add_chs' ) {
                        warn "+++++adding channels to client $id";
                        foreach ( @{$o->{channels}} ) {
                            warn "adding channel $_";
                            $l->{scratch}{ch}->{ $_ } = 1;
                        }
                    } elsif ( $action eq 'rem_ch' ) {
#                        delete $l->{scratch}{ch}->{ $o->{subscription} };
                    } elsif ( $action eq 'dis_cli' ) {
                        # XXX is close immediate, or at least set {closed}
                        # we need to send the disconnect in the cleanup
                        warn "closing client";
                        $l->close();
                        $cleanup = 1;
                    }
                }

                next;
            } else {
                warn "unhandled meta channel $o->{channel}";
                next;
            }
        }

        warn "at client loop";
        
        LISTENER:
        foreach my $l ( @clients ) {
            #next if ( $client && $l == $client );
            my $id = $l->{scratch}{id};
       
            warn "at client: $id";

            if ( $l->{closed} ) {
                warn "this client is closed: $id";
                if ( $l->{scratch}{tunnel} eq 'iframe' ) {
                    Cometd::Perlbal::Service::Connector::multiplex_send({
                        channel => '/meta/disconnect',
                        clientId => $l->{scratch}{id},
                        data => {
                            channels => [ keys %{$l->{scratch}{ch}} ],
                        },
                    });
                }
                $cleanup = 1;
                next;
            }
        
            # loop over client channels and deliver event
            # TODO more efficient
            warn "event for channel $o->{channel}";
            
            warn "channels this client $id has: ".join(',',(keys %{$l->{scratch}{ch}}));
            #if ( exists( $l->{scratch}{ch}{ $o->{channel} } ) ) {
                if ( $o->{clientId} && $o->{clientId} eq $l->{scratch}{id} ) {
                    warn "NOT sending event back to client\n";
                } else {
                    warn "******** delivering event on channel $o->{channel} : $id : $json";
                    $l->watch_write(0) if $l->write( $l->{scratch}{tunnel} eq 'iframe' ? $ijson : $json );
                    if ( $l->{scratch}{tunnel} eq 'long-polling' ) {
                        if ( !$l->{scratch}{close_after} ) {
                            $l->{scratch}{close_after} = time() + 1;
                        }
                        next;
                    }
                }
                $l->{alive_time} = $time;
            #}    
        } # end LISTENER

    }

    # TODO client channels
    
    if ( $cleanup ) {
        @clients = map {
            if ( !$_->{closed} ) {
                weaken( $_ );
                $_;
            } else {
                if ( $l->{scratch}{tunnel} && $l->{scratch}{tunnel} eq 'long-polling' ) {
                    # XXX right thing to do?
                } else {
                    Cometd::Perlbal::Service::Connector::multiplex_send({
                        channel => '/meta/disconnect',
                        clientId => $_->{scratch}{id},
                        data => {
                            channels => [ keys %{$_->{scratch}{ch}} ],
                        },
                    });
                }
                delete $ids{ $_->{scratch}{id} } if ( $ids{ $_->{scratch}{id} } );
                ();
            }
        } @clients;
    }

}

sub bcast_event {
    my $ch = shift;
    my $obj = shift;
    my $client = shift;
    my $cleanup;
    
    my $time = time();
    my $json = iframe_filter( $obj->{channel} = $ch, $obj );
    # TODO client channels
    foreach ( @clients ) {
        next if ( $client && $_ == $client );
        
        if ( $_->{closed} ) {
            $cleanup = 1;
            next;
        }
        
        $_->{alive_time} = $time;
        
        $_->watch_write(0) if $_->write( $json );
    }
    
    if ( $cleanup ) {
        @clients = map {
            if ( !$_->{closed} ) {
                weaken( $_ );
                $_;
            } else {
                Cometd::Perlbal::Service::Connector::multiplex_send({
                    channel => '/meta/disconnect',
                    clientId => $_->{scratch}{id},
                    data => {
                        channels => [ keys %{$_->{scratch}{ch}} ],
                    },
                });
                delete $ids{ $_->{scratch}{id} };
                ();
            }
        } @clients;
    }

}

sub close_overdue_clients {
    my $cleanup;
    
    my $time = time();
    
    foreach my $c ( @clients ) {
        if ( $c->{closed} ) {
            $cleanup = 1;
            next;
        }

        if ( my $after = $c->{scratch}{close_after} ) {
            if ( $after >= $time ) {
                $c->close();
            }
        }
    }
    
    return if ( !$cleanup );
    
    @clients = map {
        if ( !$_->{closed} ) {
            weaken( $_ );
            $_;
        } else {
            if ( my $tunnel = $_->{scratch}{tunnel} ) {
                # XXX
                if ( $tunnel eq 'iframe' ) {
                    Cometd::Perlbal::Service::Connector::multiplex_send({
                        channel => '/meta/disconnect',
                        clientId => $_->{scratch}{id},
                        data => {
                            channels => [ keys %{$_->{scratch}{ch}} ],
                        },
                    });
                }
            }
            delete $ids{ $_->{scratch}{id} };
            ();
        }
    } @clients;
}

sub iframe_filter {
    my ($ch, $obj) = @_;

    $obj->{channel} = $ch;

    $filter->freeze($obj);
}

    
sub time_keepalive {
    Danga::Socket->AddTimer( KEEPALIVE_TIMER, \&time_keepalive );
    
#    bcast_event( '/meta/ping' => { 'time' => time() } );
    
    close_overdue_clients();
    
#    Cometd::Perlbal::Service::Connector::multiplex_send({
#        channel => '/meta/ping',
#    });
    return 5;
}

1;
