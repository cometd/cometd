package ShortBus::Perlbal::Service;

use ShortBus;

use Carp qw( croak );

our (@listeners, %ids, $filter, $filter_package);

use constant CONNECTION_TIMER => 2;
use constant KEEPALIVE_TIMER => 5;

sub import {
    my ($class, $args) = @_;
    my $package = caller();

    croak 'ShortBus requires args as a hash ref'
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

    $filter_package = "ShortBus::" . ( delete $args->{filter} || 'Filter::JSON' );

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
 
    $service->register_hook( ShortBus => start_proxy_request => \&start_proxy_request );
    $service->register_hook( ShortBus => backend_response_received => \&backend_response );

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

sub backend_response {
    foreach (@_) { 
        warn "backend: $_\n";
    }
    return 0;
}

sub start_proxy_request {
    my Perlbal::ClientProxy $client = shift;
    
    # requires patched Perlbal
    my Perlbal::HTTPHeaders $hd = $client->{res_headers};
    unless ( $hd ) {
        warn "You are running an unpatched version of Perlbal, add line 123 of ClientProxy.pm: \$self->{res_headers} = \$primary_res_hdrs;\n";
        return 0;
    }
    
    my Perlbal::HTTPHeaders $head = $client->{req_headers};
    return 0 unless $head;
   
    my $opts;
    unless ( $opts = $hd->header('x-shortbus') ) {
        $client->_simple_response(404, 'No x-shortbus header returned from backend');
        return 1;
    }

    my %op = map {
        my ( $k, $v ) = split( /=/ );
        unless( $v ) { $v = ''; }
        $k => $v
    } split( /;\s*/, $opts );

    # parse query string
    # FIXME proper parsing
    my ( $qs ) = ( $head->request_uri =~ m/\?(.*)/ );
    my %in;
    if ( $qs ) {
        %in = map {
            next unless( /=/ );
            my ( $k, $v ) = split( /=/ );
            ( unescape( $k ) => unescape( $v ) );
        } split( /&/, $qs );
    }
   
    # pull action, domain and id from backend request
    my $action = $op{action} || 'bind';
    my $last = 0;
    if ( $in{last_eid} =~ m/^\d+$/ ) {
        $last = $in{last_eid};
    }

    if ( $action eq 'bind' && !( $op{id} && $op{domain} ) ) {
        $client->_simple_response(404, 'No client id and/or domain returned from backend');
        return 1;
    }
   
    SWITCH: {
        
        if ($action eq 'post') {
            # take posted data and insert into clients quque
            my $target = $ids{ $op{id} };
            last;
        }
        
        if ($action eq 'bind') {
            my $res = $client->{res_headers} = Perlbal::HTTPHeaders->new_response( 200 );
            $res->header( 'Content-Type', 'text/html' );
            $res->header( 'Connection', 'close' );
 
            # client id
            $client->{scratch}{id} = $op{id};
            # event id for this client
            $client->{scratch}{eid} = $last;
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
sb = function(ev,ch,obj) {
    window.last_eid = ev;
    var d=document.createElement('div');
    d.innerHTML = '<pre style="margin:0">Event:'+ev+'\\tChannel:'+ch+'\\t'+obj+'</pre>';
    document.body.appendChild(d);
};
if (window.parent.shortbus)
    window.parent.shortbus( window );
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
                $client => local => {
                    clientid => $op{id},
                    ( $last ? ( 'last' => $last ) : () )
                }
            ) );
    
            bcast_event( global => { connectionCount => scalar( @listeners ) } => $client );
    
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
        bcast_event( global => { connectionCount => scalar(@listeners) } );
    }

}

sub filter {
    my ($client, $ch, $obj) = @_;
    
    @$obj{qw( eid channel )} = ( $client->{scratch}{eid}++, $ch );
    
    $filter->freeze($obj);
}

sub connection_count {
    Danga::Socket->AddTimer( CONNECTION_TIMER, \&connection_count );
    
    my $count = scalar( @listeners );
    @listeners = map { if (!$_->{closed}) { $_; } else { delete $ids{$_->{scratch}{id}}; (); } } @listeners;
    my $ncount = scalar( @listeners );
    
    if ($ncount != $count ) {
        bcast_event( global => { connectionCount => $ncount } );
    }
    
    return 1;
}
    
sub time_keepalive {
    Danga::Socket->AddTimer( KEEPALIVE_TIMER, \&time_keepalive );
    
    bcast_event( global => { 'time' => time() } );
    
    return 5;
}

# patch perlbal
{
    no warnings 'redefine';
    sub Perlbal::ClientProxy::start_reproxy_service {
        my Perlbal::ClientProxy $self = $_[0];
        my Perlbal::HTTPHeaders $primary_res_hdrs = $_[1];
        my $svc_name = $_[2];
    
        my $svc = $svc_name ? Perlbal->service($svc_name) : undef;
        unless ($svc) {
            $self->_simple_response(404, "Vhost twiddling not configured for requested pair.");
            return 1;
        }
    
        $self->{backend_requested} = 0;
        $self->{backend} = undef;
        # start patch
        $self->{res_headers} = $primary_res_hdrs;
        # end patch
    
        $svc->adopt_base_client($self);
    }
}


1;
