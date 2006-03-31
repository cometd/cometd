package Perlbal::Plugin::ShortBus;

use strict;
use warnings;

use Data::GUID;

our @listeners;
our $plugin;

sub register {
    shift;
    my $service = shift;

    Perlbal::Socket::register_callback( 1, sub {
        my $count = scalar( @listeners );
        @listeners = grep { !$_->{closed} } @listeners;
        if ( scalar( @listeners ) != $count ) {
            bcast_event( { connectionCount => $count } );
        }
        return 1;
    } );
    
    Perlbal::Socket::register_callback( 5, sub {
        bcast_event( { "time" => time() } );
        return 5;
    } );
    
    $service->register_hook( "ShortBus", "start_proxy_request", \&start_proxy_request );

    return 1;
}

sub unregister {
    return 1;
}

sub load {
    $plugin = ShortBus::Filter::JSON->new();
    return 1;
}

sub unload {
    @listeners = ();
    $plugin = undef;
    return 1;
}

sub start_proxy_request {
    my Perlbal::ClientProxy $self = shift;
    my Perlbal::HTTPHeaders $head = $self->{req_headers};
    return 0 unless $head;
    my $uri = $head->request_uri;
    
    return 0 unless $uri =~ m{^/shortbus/(\w+)(?:\?last=(\d+))?$};
    my ( $action, $last ) = ( $1, $2 || 0 );

    my $send = sub {
        shift;
        my $body = shift;
        
        my $res = $self->{res_headers} = Perlbal::HTTPHeaders->new_response( 200 );
        $res->header( "Content-Type", "text/html" );
        $res->header( "Content-Length", length($$body) );
        $self->setup_keepalive( $res );

        $self->state( "xfer_resp" );
        $self->tcp_cork( 1 );
        $self->write( $res->to_string_ref );
        
        unless ( $self->{req_headers}
            && $self->{req_headers}->request_method eq "HEAD" ) {
            $self->write( $body );
        }
        
        $self->write( sub { $self->http_response_sent; } );
    };

    if ( $action eq "register" ) {
        my $res = $self->{res_headers} = Perlbal::HTTPHeaders->new_response( 200 );
        $res->header( "Content-Type", "text/html" );
        $res->header( "Connection", "close" );
        
        # TODO get guid from backend return header
        $self->{scratch}{guid} = Data::GUID->new();
        push( @listeners, $self );
        
        $self->write( $res->to_string_ref );
       
        $self->write( \ qq|<html>
<body>
<script>
var domain = document.domain.split('.').reverse();
document.domain = [domain[2],domain[1],domain[0]].join('.');
if (window.parent.log) {
    try {
        log = window.parent.log;
        log.warn = window.parent.log.warn;
        log.error = window.parent.log.error;
        log.debug = window.parent.log.debug;
        log.debug('document domain for inner inner iframe is '+document.domain);
        sb = window.parent.log; 
   } catch(e) { };
}
</script>
| );
       
        bcast_event( { connectionCount => scalar(@listeners) } );
        
        my $last_ret = $self->write( filter(
            {
                id => "".$self->{scratch}{guid},
                ( $last ? ( "last" => $last ) : () )
            }
        ) );
        
        if ($last) {
            # TODO queue
            if ( $self->{scratch}{queue} ) {
                foreach ( @{$self->{scratch}{queue}} ) {
                    next if ( $_->[0] < $last );
                    $last_ret = $self->write( $_->[1] );
                }
            }
        }

        $self->watch_write(0) if ( $last_ret );
        
        return 1;
    }

    if ( $action eq "bcast" ) {
        # TODO inject
        bcast_event( { data => "" } );

        $send->( "text/plain", \ "OK" );
        
        return 1;
    }

    return 0;
}


sub bcast_event {
    my $obj = shift;
    my $cleanup = 0;
    
    my $time = time();

    foreach ( @listeners ) {
        if ( $_->{closed} ) {
            $cleanup = 1;
            next;
        }
        
        $_->{alive_time} = $time;
        $_->watch_write(0) if $_->write( filter( $obj ) );
    }
        
    
    if ( $cleanup ) {
        @listeners = grep { !$_->{closed} } @listeners;
        bcast_event( { connectionCount => scalar(@listeners) } );
    }

}


sub filter {
    $plugin->freeze(shift);
}

1;

package ShortBus::Filter::JSON;

use JSON;

sub new {
    bless({},shift);
}

sub freeze {
    shift;
    return "<script>\nsb('".objToJson(shift)."');\n</script>\n";
}

sub thaw {
    shift;
    return jsonToObj(shift);
}

1;
