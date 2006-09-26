package Cometd::Perlbal::Service;

use Cometd;

use Carp qw( croak );
use Scalar::Util qw( weaken );
use Cometd::Perlbal::Service::Connector;

our ( @listeners, %ids, $filter, $filter_package );

use constant KEEPALIVE_TIMER => 5;

sub import {
    my ($class, $args) = @_;
    my $package = caller();

    croak 'Cometd requires args as a hash ref'
        if ($args && ref($args) ne 'HASH');

    my %exports = qw(
        listeners   \@listeners
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
    @listeners = %ids = ();
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
   
    # pull action, domain and id from backend request
    my $action = $op{action} || 'connect';
    my $last = 0;
    #if ( exists($in->{last_eid}) && $in->{last_eid} =~ m/^\d+$/ ) {
    #    $last = $in->{last_eid};
    #}

    if ( $action eq 'connect' && !( $op{id} && $op{domain} ) ) {
        warn "no client id and/or domain set";
        $client->_simple_response(404, 'No client id and/or domain returned from backend');
        return 1;
    }
   
    SWITCH: {
        
        if ( $action eq 'connect' ) {
            my $res = $client->{res_headers} = Perlbal::HTTPHeaders->new_response( 200 );
            $res->header( 'Content-Type', 'text/html' );
            # close
            $res->header( 'Connection', 'close' );
 
            #$op{id} = $in->{id} if ( $in->{id} );
            
            # client id
            $client->{scratch}{id} = $op{id};
            # event id for this client
            $client->{scratch}{eid} = $last;

            if ( $op{channels} ) {
                # XXX negative subscription?
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
                        channels => $cli->{scratch}{ch},
                        previous => 1,
                    },
                });
                $cli->close();
            }
            
            # id to listener obj map
            $ids{ $op{id} } = $client;
            weaken( $ids{ $op{id} } );
            @listeners = grep { defined } ( @listeners, $client );
            foreach (@socket_list) { weaken( $_ ); }
        
            Cometd::Perlbal::Service::Connector::multiplex_send({
                channel => '/meta/connect',
                clientId => $op{id},
                data => {
                    channels => $client->{scratch}{ch}
                },
            });
    
            $client->write( $res->to_string_ref );
            $client->tcp_cork( 1 );
  
            $op{domain} =~ s/'/\\'/g;

            $client->write( qq|<html><body><script type="text/javascript">
// <![CDATA[ 
document.domain = '$op{domain}';
window.last_eid = $last;
window.onload = function() {
    window.location.href = window.location.href.replace( /last_eid=\\d+/, 'last_eid='+window.last_eid );
};
deliver = function(ch,obj) {
    var ev;
    if ( obj.eventId )
        ev = window.last_eid = obj.eventId;
    var d=document.createElement('div');
    d.innerHTML = '<pre style="margin:0">EventId:'+ev+'\\tChannel:'+ch+'\\t'+obj+'</pre>';
    document.body.appendChild(d);
};
if (window.parent.cometd)
    window.parent.cometd.setup( window );
// ]]>
</script>
| );
 
            $client->watch_write( 0 ) if $client->write( filter(
                '/meta/connect' => {
                    successful => 'true',
                    error => '',
                    clientId => $op{id},
                    connectionId => '/meta/connections/'.$op{id}, # XXX
                    timestamp => time(), # XXX
                    data => {
                        channels => $client->{scratch}{ch}
                    },
                    ( $last ? ( 'last' => $last ) : () )
                }
            ) );
    
            last;
            # test code
    
            Danga::Socket->AddTimer( 15, sub {
                @listeners = map {
                    if ( !$_->{closed} ) {
                        if ($_ != $client) {
                            weaken( $_ );
                            $_;
                        } else {
                            $_->watch_write( 0 ) if $_->write( '</body></html>' );
                            $_->close();
                            Cometd::Perlbal::Service::Connector::multiplex_send({
                                channel => '/meta/disconnect',
                                clientId => $_->{scratch}{id},
                                data => {
                                    channels => $_->{scratch}{ch},
                                },
                            });
                            delete $ids{ $_->{scratch}{id} };
                            ();
                        }
                    } else {
                        delete $ids{ $_->{scratch}{id} };
                        ();
                    }
                } @listeners;
            } );
            
            last;
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
        foreach my $l ( @listeners ) {
            next if ( $client && $l == $client );
        
            if ( $l->{closed} ) {
                Cometd::Perlbal::Service::Connector::multiplex_send({
                    channel => '/meta/disconnect',
                    clientId => $l->{scratch}{id},
                    data => {
                        channels => $l->{scratch}{ch}
                    },
                });
                $cleanup = 1;
                next;
            }
        
            # loop over client channels and deliver event
            # TODO more efficient
            foreach ( keys %{ $l->{scratch}{ch} } ) {
                if ( $_ eq $o->{channel} ) {
                    $l->watch_write(0) if $l->write( $json );
                    $l->{alive_time} = $time;
                    next LISTENER;
                }
            }    
        } # end LISTENER

    }

    # TODO client channels
    
    if ( $cleanup ) {
        @listeners = map {
            if ( !$_->{closed} ) {
                weaken( $_ );
                $_;
            } else {
                Cometd::Perlbal::Service::Connector::multiplex_send({
                    channel => '/meta/disconnect',
                    clientId => $_->{scratch}{id},
                    data => {
                        channels => $_->{scratch}{ch}
                    },
                });
                delete $ids{ $_->{scratch}{id} };
                ();
            }
        } @listeners;
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
    foreach ( @listeners ) {
        next if ( $client && $_ == $client );
        
        if ( $_->{closed} ) {
            $cleanup = 1;
            next;
        }
        
        $_->{alive_time} = $time;
        
        $_->watch_write(0) if $_->write( $json );
    }
    
    if ( $cleanup ) {
        @listeners = map {
            if ( !$_->{closed} ) {
                weaken( $_ );
                $_;
            } else {
                Cometd::Perlbal::Service::Connector::multiplex_send({
                    channel => '/meta/disconnect',
                    clientId => $_->{scratch}{id},
                    data => {
                        channels => $_->{scratch}{ch}
                    },
                });
                delete $ids{ $_->{scratch}{id} };
                ();
            }
        } @listeners;
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
    
    Cometd::Perlbal::Service::Connector::multiplex_send({
        channel => '/meta/ping',
        clientId => 'none',
        data => {},
    });
    return 5;
}

1;
