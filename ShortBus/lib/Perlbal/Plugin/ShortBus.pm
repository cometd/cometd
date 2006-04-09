package Perlbal::Plugin::ShortBus;

use strict;
use warnings;

use Data::GUID;

our @listeners;
our %ids;
our $plugin;

sub register {
    shift;
    my $service = shift;
   
    Danga::Socket->AddTimer( 1, \&connection_count );
    Danga::Socket->AddTimer( 5, \&time_keepalive );
    
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
    @listeners = %ids = ();
    $plugin = undef;
    return 1;
}

sub start_proxy_request {
    my Perlbal::ClientProxy $self = shift;
    my Perlbal::HTTPHeaders $head = $self->{req_headers};
    return 0 unless $head;
    my $uri = $head->request_uri;
    
#    return 0 unless $uri =~ m{^/shortbus/(\w+)(?:\?last=(\d+))?$};
#    my ( $action, $last ) = ( $1, $2 || 0 );
    my $action = 'register';
    my $last = 0;

    my $send = sub {
        shift;
        my $body = shift;
        
        my $res = $self->{res_headers} = Perlbal::HTTPHeaders->new_response( 200 );
        $res->header( "Content-Type", "text/html" );
        $res->header( "Content-Length", length( $$body ) );
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
        my $id = Data::GUID->new();
        $self->{scratch}{guid} = $id = "$id";
        $ids{ $id } = $self;
        push( @listeners, $self );
        
        $self->write( $res->to_string_ref );
        $self->tcp_cork( 1 );
       
        $self->write( qq|<html>
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
                id => $self->{scratch}{guid},
                ( $last ? ( "last" => $last ) : () )
            }
        ) );
        
        if ( $last ) {
            # TODO queue
            if ( $self->{scratch}{queue} ) {
                foreach ( @{$self->{scratch}{queue}} ) {
                    next if ( $_->[ 0 ] < $last );
                    $last_ret = $self->write( $_->[ 1 ] );
                }
            }
        }

        $self->watch_write( 0 ) if ( $last_ret );

        return 1;
    }

    if ( $action eq "stream" ) {
        
        # create filehandle for reading
#        my $data = '';
#        Perlbal::AIO::aio_read($read_handle, 0, 2048, $data, sub {
            # got data? undef is error
#            return $self->_simple_response(500) unless $_[0] > 0;

#            $ids{$id}->write( filter( { event => 'stream', data => $data } ) );
            
            # reenable writes after we get data
            #$self->tcp_cork(1); # by setting reproxy_file_offset above, it won't cork, so we cork it
            #$self->watch_write(1);
#        });
        
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
        @listeners = map { if (!$_->{closed}) { $_; } else { delete $ids{$_->{scratch}{guid}}; (); } } @listeners;
        bcast_event( { connectionCount => scalar(@listeners) } );
    }

}

sub filter {
    $plugin->freeze(shift);
}

sub connection_count {
    Danga::Socket->AddTimer( 1, \&connection_count );
    
    my $count = scalar( @listeners );
    @listeners = map { if (!$_->{closed}) { $_; } else { delete $ids{$_->{scratch}{guid}}; (); } } @listeners;
    if ( scalar( @listeners ) != $count ) {
        bcast_event( { connectionCount => $count } );
    }
    return 1;
}
    
sub time_keepalive {
    Danga::Socket->AddTimer( 5, \&time_keepalive );
    
    bcast_event( { "time" => time() } );
    return 5;
}

1;

package ShortBus::Filter::JSON;

use JSON;

sub new {
    bless( {},shift );
}

sub freeze {
    shift;
    return "<script>sb('".objToJson(shift)."');</script>\n";
}

sub thaw {
    shift;
    return jsonToObj(shift);
}

1;
