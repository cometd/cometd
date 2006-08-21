package Cometd::Perlbal::Service;

use Cometd;

use Carp qw( croak );

our ( @listeners, %ids, $filter, $filter_package );

use constant CONNECTION_TIMER => 3;
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

    $filter_package = "Cometd::" . ( delete $args->{filter} || 'Filter::JSON' );

    my @unknown = sort keys %$args;
    croak "Unknown $class import arguments: @unknown" if @unknown;

    eval "use $filter_package";
    croak $@ if ($@);
}

sub register {
    shift;
    my $service = shift;
   
    Danga::Socket->AddTimer( CONNECTION_TIMER, \&connection_count );
    Danga::Socket->AddTimer( KEEPALIVE_TIMER, \&time_keepalive );
 
    $service->register_hook( Cometd => start_proxy_request => \&start_proxy_request );

    return 1;
}

sub unregister {
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
        $client->_simple_response(404, 'No x-cometd header sent');
        return 1;
    }

    my %op = map {
        my ( $k, $v ) = split( /=/ );
        unless( $v ) { $v = ''; }
        lc($k) => $v
    } split( /;\s*/, $opts );

    # parse query string
    my $in = params( $client );
   
    # pull action, domain and id from backend request
    my $action = $op{action} || 'bind';
    my $last = 0;
    if ( exists($in->{last_eid}) && $in->{last_eid} =~ m/^\d+$/ ) {
        $last = $in->{last_eid};
    }

    if ( $action eq 'bind' && !( $op{id} && $op{domain} ) ) {
        $client->_simple_response(404, 'No client id and/or domain returned from backend');
        return 1;
    }
   
    SWITCH: {
        
        if ( $action eq 'post' ) {
            unless ( defined( $op{id} ) ) {
                $client->_simple_response(404, "No id param defined in X-SHORTBUS header");
                return 1;
            }
            foreach (qw( id channel )) {
                $in->{$_} = $op{$_} if ($op{$_});

                unless ( defined( $in->{$_} ) ) {
                    $client->_simple_response(404, "No $_ param defined");
                    return 1;
                }
            }
            warn "event inject, channel: $in->{channel} for id: $op{id}, and data $in->{data}";
            # take posted data and insert into clients queque
            if ( $op{id} eq '*' ) {
                bcast_event( $in->{channel} => $in->{data} );
            } else {
                my $cli = $ids{ $op{id} };
                $cli->write( filter( $cli => $in->{channel} => $in->{data} ) );
            }
            $client->_simple_response(200, 'OK');
            return 1;
        }
        
        if ( $action eq 'bind' ) {
            my $res = $client->{res_headers} = Perlbal::HTTPHeaders->new_response( 200 );
            $res->header( 'Content-Type', 'text/html' );
            $res->header( 'Connection', 'close' );
 
            if ($in->{id}) {
                $op{id} = $in->{id};
            }
            
            # client id
            $client->{scratch}{id} = $op{id};
            # event id for this client
            $client->{scratch}{eid} = $last;

            # close already connected client if any
            if ( my $cli = delete $ids{ $op{id} } ) {
                $cli->write( filter(
                    $client => '/meta/local' => {
                        event => 'closing',
                        reason => 'Closing previous connection',
                    }
                ) . '</body></html>' );
                $cli->close();
            }
            
            # id to listener obj map
            $ids{ $op{id} } = $client;
            push( @listeners, $client );
    
            $client->write( $res->to_string_ref );
            $client->tcp_cork( 1 );
   
            $client->write( qq|<html><body><script>
<!--
document.domain = '$op{domain}';
window.last_eid = $last;
window.onload = function() {
    window.location.href = window.location.href.replace( /last_eid=\\d+/, 'last_eid='+window.last_eid );
};
cometd = function(ev,ch,obj) {
    window.last_eid = ev;
    var d=document.createElement('div');
    d.innerHTML = '<pre style="margin:0">Event:'+ev+'\\tChannel:'+ch+'\\t'+obj+'</pre>';
    document.body.appendChild(d);
};
if (window.parent.cometd)
    window.parent.cometd( window );
-->
</script>
| );
 
            my $last_ret;
            if ( $last ) {
                if ( $client->{scratch}{queue} ) {
                    my $lastidx;
                    for my $i ( 0 .. $#{$client->{scratch}{queue}} ) {
                        if ( $client->{scratch}{queue}[ $i ]->[ 0 ] < $last ) {
                            $lastidx = $i;
                            next;
                        }
                        $last_ret = $client->write( $client->{scratch}{queue}[ $i ]->[ 1 ] );
                    }
                    # splice out  previous events
                    if ( defined( $lastidx ) ) {
                        splice( @{$client->{scratch}{queue}}, 0, $lastidx );
                    }
                }
            } 
   
            $last_ret = $client->write( filter(
                $client => '/meta/local' => {
                    clientid => $op{id},
                    ( $last ? ( 'last' => $last ) : () )
                }
            ) );
    
            bcast_event( '/meta/global' => { connectionCount => scalar( @listeners ) } => $client );
    
            $client->watch_write( 0 ) if ( $last_ret );
    
            last;
            # test code
    
            Danga::Socket->AddTimer( 15, sub {
                @listeners = map {
                    if (!$_->{closed}) {
                        if ($_ != $client) {
                            $_;
                        } else {
                            $_->watch_write( 0 ) if $_->write( '</body></html>' );
                            $_->close();
                            delete $ids{$client->{scratch}{id}};
                            ();
                        }
                    } else {
                        delete $ids{$_->{scratch}{id}};
                        ();
                    }
                } @listeners;
            } );
            
            last;
        }
    }
    
    return 1;
}


sub bcast_event {
    my $ch = shift;
    my $obj = shift;
    my $client = shift;
    my $cleanup = 0;
    
    my $time = time();

    # TODO client channels
    foreach ( @listeners ) {
        next if ( $client && $_ == $client );
        
        if ( $_->{closed} ) {
            $cleanup = 1;
            next;
        }
        
        $_->{alive_time} = $time;
        
        $_->watch_write(0) if $_->write( filter( $_, $obj->{channel} = $ch, $obj ) );
    }
        
    
    if ( $cleanup ) {
        @listeners = map {
            if (!$_->{closed}) {
                $_;
            } else {
                delete $ids{$_->{scratch}{id}};
                ();
            }
        } @listeners;
        bcast_event( '/meta/global' => { connectionCount => scalar(@listeners) } );
    }

}

sub filter {
    my ($client, $ch, $obj) = @_;
   
    unless ( ref( $obj ) ) {
        eval {
           $obj = $filter->thaw( $obj );
        };
        if ($@) {
            warn "$@\n";
            $obj = { event => 'error', error => $@ };
        }
    }
   
    @$obj{qw( eid channel )} = ( ++$client->{scratch}{eid}, $ch );

    $filter->freeze($obj);
}

sub connection_count {
    Danga::Socket->AddTimer( CONNECTION_TIMER, \&connection_count );
    
    my $count = scalar( @listeners );
    @listeners = map { if (!$_->{closed}) { $_; } else { delete $ids{$_->{scratch}{id}}; (); } } @listeners;
    my $ncount = scalar( @listeners );
    
    if ($ncount != $count ) {
        bcast_event( '/meta/global' => { connectionCount => $ncount } );
    }
    
    return 1;
}
    
sub time_keepalive {
    Danga::Socket->AddTimer( KEEPALIVE_TIMER, \&time_keepalive );
    
    bcast_event( '/meta/global' => { 'time' => time() } );
    
    return 5;
}

1;
