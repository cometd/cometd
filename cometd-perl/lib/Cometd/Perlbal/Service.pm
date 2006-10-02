package Cometd::Perlbal::Service;

use Cometd;

use Carp qw( croak );
use Scalar::Util qw( weaken );
use Cometd::Perlbal::Service::Connector;
use URI::Escape;
use JSON;

our ( @clients, %ids, $filter, $filter_package );

use constant KEEPALIVE_TIMER => 20;

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
   
    # requires patched Perlbal
    my Perlbal::HTTPHeaders $hd = $client->{res_headers};
    unless ( $hd ) {
        $hd = $head;
        # XXX no res_headers are available when cometd isn't reproxied
        warn "You are running an old version of Perlbal, please use check out the lastest build in svn at code.sixapart.com\n";
        return 0;
    }

    my $opts;
    unless ( $opts = $hd->header('x-cometd') ) {
        warn "no x-cometd header sent";
        $client->_simple_response(404, 'No x-cometd header sent');
        return 1;
    }

    my %op = map {
        my ( $k, $v ) = split( /=/ );
        unless( $v ) { $v = ''; }
        lc($k) => $v
    } split( /;\s*/, $opts );

    # parse query string
    #my $in = params( $client );
   
    require Data::Dumper;
    warn Data::Dumper->Dump([$hd]);
   
    my $event = {};
    
    if ( my $msg = $hd->header('x-cometd-data') ) {
        $msg = uri_unescape( $msg );
        warn "!!!! $msg\n";
        if ( $msg =~ m/^\[/ ) {
            my $o = eval { jsonToObj( $msg ); };
            if ( $@ ) {
                warn $@;
            } else {
                if ( ref($o) eq 'ARRAY' ) {
                    foreach my $m ( @$o ) {
                        next unless ( ref( $m ) eq 'HASH' );
                        if ( $m->{channel} && $m->{channel} eq '/meta/handshake' ) {
                            $op{action} = 'handshake';
                            $event = $m;
                            last;
                        }
                    }
                }
            }
        }
    }
   
    # pull action, domain and id from backend request
    my $action = $op{action} || 'connect';
    $op{tunnelType} ||= 'http-polling';
    my $last_eid = 0;
    #if ( exists($in->{last_eid}) && $in->{last_eid} =~ m/^\d+$/ ) {
    #    $last_eid = $in->{last_eid};
    #}

    warn "action: $action";

    if ( $action eq 'handshake' ) {
    
        unless ( $op{id} ) {
            warn "no client id set";
            $client->_simple_response(404, 'No client id returned from backend');
            return 1;
        }
        
        # $event = [{"version":0.1,"minimumVersion":0.1,"channel":"/meta/handshake"}]
        my $res = $client->{res_headers} = Perlbal::HTTPHeaders->new_response( 200 );
        $res->header( 'Content-Type', 'text/html' );
        # close
        $res->header( 'Connection', 'close' );

        $res->header( 'Set-Cookie', $hd->header( 'set-cookie' ) )
            if ( $hd->header( 'set-cookie' ) );
	    
        $client->write( $res->to_string_ref );
        $client->tcp_cork( 1 );
        $client->watch_write( 0 ) if $client->write( objToJson({
            channel => '/meta/handshake',
			version => 0.1,
  			minimumVersion => 0.1,
            supportedConnectionTypes => [
                'http-polling'
            ],
			clientId => $op{id},
			authSuccessful => 'true',
			#authToken => "SOME_NONCE_THAT_NEEDS_TO_BE_PROVIDED_SUBSEQUENTLY",
        }) );
        $client->close();
        
        return 1;
    }
    
    if ( $action eq 'connect' ) {

        unless ( $op{id} && $op{domain} ) {
            warn "no client id and/or domain set";
            $client->_simple_response(404, 'No client id and/or domain returned from backend');
            return 1;
        }
   
        my $res = $client->{res_headers} = Perlbal::HTTPHeaders->new_response( 200 );
        $res->header( 'Content-Type', 'text/html' );
        # close
        $res->header( 'Connection', 'close' );
 
        $res->header( 'Set-Cookie', $hd->header( 'set-cookie' ) )
            if ( $hd->header( 'set-cookie' ) );
        
        #$op{id} = $in->{id} if ( $in->{id} );
        
        # client id
        $client->{scratch}{id} = $op{id};
        # event id for this client
        $client->{scratch}{eid} = $last_eid;

        if ( $op{channels} ) {
            $client->{scratch}{ch} = { map { $_ => 1 } split( /;\s?/, $op{channels} ) };
        } else {
            $client->{scratch}{ch} = {};
        }
        
        # close already connected client if any
        if ( my $cli = delete $ids{ $op{id} } ) {
            $cli->write( filter(
                '/meta/disconnect' => {
                    reason => 'Closing previous connection',
                }
            ) . '</body></html>' );
            Cometd::Perlbal::Service::Connector::multiplex_send({
                channel => '/meta/disconnect',
                clientId => $op{id},
                data => {
                    channels => [ keys %{$cli->{scratch}{ch}} ],
                    previous => 1,
                },
            });
            $cli->close();
        }
        
        # id to listener obj map
        $ids{ $op{id} } = $client;
        weaken( $ids{ $op{id} } );
        @clients = grep { defined } ( @clients, $client );
        foreach (@socket_list) { weaken( $_ ); }
    
        Cometd::Perlbal::Service::Connector::multiplex_send({
            channel => '/meta/connect',
            clientId => $op{id},
            data => {
                channels => [ keys %{$client->{scratch}{ch}} ],
            },
        });

        $client->write( $res->to_string_ref );
        $client->tcp_cork( 1 );
  
        $client->{scratch}{tunnel} = $op{tunnelType};

        if ( $op{tunnelType} eq 'http-polling' ) {

            unless ( $op{id} ) {
                warn "no client id set";
                $client->_simple_response(404, 'No client id returned from backend');
                return 1;
            }
        
            # $event = [{"version":0.1,"minimumVersion":0.1,"channel":"/meta/handshake"}]
            my $res = $client->{res_headers} = Perlbal::HTTPHeaders->new_response( 200 );
            $res->header( 'Content-Type', 'text/html' );
            # close
            $res->header( 'Connection', 'close' );

            $res->header( 'Set-Cookie', $hd->header( 'set-cookie' ) )
                if ( $hd->header( 'set-cookie' ) );
	    
            $client->write( $res->to_string_ref );
            $client->tcp_cork( 1 );
  
            # XXX set timer or let client drop connection?
            
            return 1;
                    
        } elsif ( $op{tunnelType} eq 'iframe' ) {
            
            $op{domain} =~ s/'/\\'/g;

            $client->write( qq~<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN"
	"http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">

<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en">
	<head>
		<title>cometd: The Long Tail of Comet</title>
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
		<h4>cometd: The Long Tail of Comet</h4>
	</body>
</html>~ );
 
        $client->watch_write( 0 ) if $client->write( filter(
            '/meta/connect' => {
                successful => 'true',
                error => '',
                clientId => $op{id},
                connectionId => '/meta/connections/'.$op{id}, # XXX
                timestamp => time(), # XXX
                data => {
                    channels => [ keys %{$client->{scratch}{ch}} ],
                },
                ( $last_eid ? ( 'last' => $last_eid ) : () )
            }
        ) );
        
        } elsif ( $op{tunnelType}  ) {
            
        }
    }
    
    return 1;
}

sub handle_event {
    my $objs = shift;
    my $cleanup = 0;

    $objs = [ $objs ] unless ( ref( $objs ) eq 'ARRAY' );
    
    my $time = time();

    foreach $o ( @$objs ) {
        next unless ( ref( $o ) eq 'HASH' );
        next if ( !$o->{channel} );
        
        $o->{timestamp} = $time; # XXX
        
        my $json = filter( $o->{channel}, $o );
        
        if ( $o->{channel} =~ m~^/meta/clients/(.*)~ ) {
            if ( exists( $ids{ $1 } ) ) {
                my $l = $ids{ $1 };
                $l->watch_write(0) if $l->write( $json );
                $l->{alive_time} = $time;
                next;
            }
        }

        my $id;
        my $action;
        if ( $o->{channel} =~ m~^/meta/(.*)~ ) {
            my $meta = $1;
            next if ( !$o->{clientId} );

            if ( $meta = 'subscribe' && $o->{subscription} ) {
                if ( $o->{successful} ne 'false' ) {
                    # add channel
                    $action = 'add_ch';
                }
                $locate = $o->{clientId};
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
                next if ( !$l );
        
                $l->watch_write(0) if $l->write( $json );
                $l->{alive_time} = $time;
            
                if ( $action ) {
                    if ( $action eq 'add_ch' ) {
                        $l->{scratch}{ch}->{ $o->{subscription} } = 1;
                    } elsif ( $action eq 'rem_ch' ) {
                        delete $l->{scratch}{ch}->{ $o->{subscription} };
                    } elsif ( $action eq 'dis_cli' ) {
                        # XXX is close immediate, or at least set {closed}
                        # we need to send the disconnect in the cleanup
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

        
        LISTENER:
        foreach my $l ( @clients ) {
            next if ( $client && $l == $client );
        
            if ( $l->{closed} ) {
                Cometd::Perlbal::Service::Connector::multiplex_send({
                    channel => '/meta/disconnect',
                    clientId => $l->{scratch}{id},
                    data => {
                        channels => [ keys %{$l->{scratch}{ch}} ],
                    },
                });
                $cleanup = 1;
                next;
            }
        
            # loop over client channels and deliver event
            # TODO more efficient
            if ( exists( $l->{scratch}{ch}{ $o->{channel} } ) ) {
                warn "delivering event on channel $o->{channel} : $json";
                $l->watch_write(0) if $l->write( $json );
                $l->{alive_time} = $time;
                # XXX check tunnel, set disconnect timers, etc
            }    
        } # end LISTENER

    }

    # TODO client channels
    
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

sub bcast_event {
    my $ch = shift;
    my $obj = shift;
    my $client = shift;
    my $cleanup;
    
    my $time = time();
    my $json = filter( $obj->{channel} = $ch, $obj );
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

sub filter {
    my ($ch, $obj) = @_;

    $obj->{channel} = $ch;

    $filter->freeze($obj);
}

    
sub time_keepalive {
    Danga::Socket->AddTimer( KEEPALIVE_TIMER, \&time_keepalive );
    
    bcast_event( '/meta/ping' => { 'time' => time() } );
    
#    Cometd::Perlbal::Service::Connector::multiplex_send({
#        channel => '/meta/ping',
#    });
    return 5;
}

1;
