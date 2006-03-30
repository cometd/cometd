package Perlbal::Plugin::ShortBus;

use strict;
use warnings;

use Data::GUID;

our @listeners;
our %lmap;

sub register {
    shift;
    my $service = shift;

    Perlbal::Socket::register_callback( 1, sub {
        my $count = scalar( grep { !$_->{closed} } @listeners );
        if ( scalar( @listeners ) != $count ) {
            bcast_event( "{connectionCount:$count}" );
        }
        return 1;
    } );
    
    Perlbal::Socket::register_callback( 5, sub {
        bcast_event( "{ka:1}" );
        return 5;
    } );
    
    $service->register_hook( "ShortBus", "start_proxy_request", \&start_proxy_request );

    return 1;
}

sub unregister {
    return 1;
}

sub load {
    return 1;
}

sub unload {
    return 1;
}

sub start_proxy_request {
    my Perlbal::ClientProxy $self = shift;
    my Perlbal::HTTPHeaders $head = $self->{req_headers};
    return 0 unless $head;
    my $uri = $head->request_uri;
    
    return 0 unless $uri =~ m{^/shortbus/(\w+)(?:\?(.*))?};
    my ( $mode, $q ) = ( $1, $2 );

    my $send = sub {
        shift;
        my $body = shift;
        
        my $res = $self->{res_headers} = Perlbal::HTTPHeaders->new_response( 200 );
        $res->header( "Content-Type", "text/plain" );
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

    if ( $mode eq "register" ) {
        my $res = $self->{res_headers} = Perlbal::HTTPHeaders->new_response( 200 );
        $res->header( "Content-Type", "text/plain" );
        $res->header( "Connection", "close" );
        
        # TODO get guid from cookie
        $lmap{"$self"} = Data::GUID->new();
        push( @listeners, $self );
        
        $self->write( $res->to_string_ref );
        $self->write( "<script>sb({id:'".$lmap{"$self"}."'});</script>\n" );
        bcast_event( "{connectionCount:".scalar(@listeners)."}" );
        
        return 1;
    }

    if ( $mode eq "bcast" ) {
        bcast_event( "$q" );

        $send->( "text/plain", \ "OK" );
        
        return 1;
    }

    return 0;
}


sub bcast_event {
    my $json = shift;
    my $cleanup = 0;
    
    my $time = time();

    foreach ( @listeners ) {
        if ( $_->{closed} ) {
            delete $lmap{"$_"};
            $cleanup = 1;
            next;
        }
        $_->{alive_time} = $time;
        $_->write( "<script>sb('$json');</script>\n" );
    }
    
    if ( $cleanup ) {
        @listeners = grep { !$_->{closed} } @listeners;
        bcast_event( "{connectionCount:".scalar(@listeners)."}" );
    }
}

1;
