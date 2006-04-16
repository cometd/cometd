package Perlbal::Plugin::ShortBus;

use strict;
use warnings;

our (@listeners, %ids, %hex_chr, $plugin);

use constant CONNECTION_TIMER => 2;
use constant KEEPALIVE_TIMER => 5;

sub register {
    shift;
    my $service = shift;
   
    Danga::Socket->AddTimer( CONNECTION_TIMER, \&connection_count );
    Danga::Socket->AddTimer( KEEPALIVE_TIMER, \&time_keepalive );
    
    $service->register_hook( "ShortBus", "start_proxy_request", \&start_proxy_request );

    return 1;
}

sub unregister {
    return 1;
}

sub load {
    for ( 0 .. 255 ) {
        $hex_chr{lc( sprintf "%02x", $_ )} = chr($_);
    }
    $plugin = ShortBus::Filter::JSON->new();
    return 1;
}

sub unload {
    @listeners = %ids = %hex_chr = ();
    $plugin = undef;
    return 1;
}

sub start_proxy_request {
    my Perlbal::ClientProxy $self = shift;
    # requires patched Perlbal
    my Perlbal::HTTPHeaders $hd = $self->{res_headers};
    unless ($hd) {
        warn "You are running an unpatched version of Perlbal, add line 123 of ClientProxy.pm: \$self->{res_headers} = \$primary_res_hdrs;\n";
        return 0;
    }
    my Perlbal::HTTPHeaders $head = $self->{req_headers};
    return 0 unless $head;
   
    my $opts;
    unless ( $opts = $hd->header('x-shortbus') ) {
        $self->_simple_response(404, "No x-shortbus header returned from backend");
        return 1;
    }

    my %op = map { split(/=/) } split(/;\s*/, $opts);

    unless ($op{id} && $op{domain}) {
        $self->_simple_response(404, "No client id and/or domain returned from backend");
        return 1;
    }
    
    # parse query string
    my ($qs) = ( $head->request_uri =~ m/\?(.*)/ );
    my %in = map {
        my ( $k,$v ) = split(/=/);
        $v = unescape($v);
        ($k => $v);
    } split(/&/, $qs);
    
    my $action = $op{action} || 'register';
    my $last = $in{last} || 0;
    my ($id, $domain) = ($op{id}, $op{domain});

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
        
        unless ( $head->request_method eq "HEAD" ) {
            $self->write( $body );
        }
        
        $self->write( sub { $self->http_response_sent; } );
    };

    if ( $action eq "register" ) {
        my $res = $self->{res_headers} = Perlbal::HTTPHeaders->new_response( 200 );
        $res->header( "Content-Type", "text/html" );
        $res->header( "Connection", "close" );
        
        $self->{scratch}{guid} = $id;
        $ids{ $id } = $self;
        push( @listeners, $self );
        
        $self->write( $res->to_string_ref );
        $self->tcp_cork( 1 );
       
        $self->write( qq|<html>
<body>
<script>
<!--
document.domain = '$domain';
window.sb = function(json) { alert(json); };
if (window.parent.shortbus)
    window.parent.shortbus( window );
-->
</script>
| );
       
        bcast_event( { connectionCount => scalar(@listeners) } );
        
        my $last_ret = $self->write( filter(
            {
                id => $id,
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
    Danga::Socket->AddTimer( CONNECTION_TIMER, \&connection_count );
    
    my $count = scalar( @listeners );
    @listeners = map { if (!$_->{closed}) { $_; } else { delete $ids{$_->{scratch}{guid}}; (); } } @listeners;
    if ( scalar( @listeners ) != $count ) {
        bcast_event( { connectionCount => $count } );
    }
    return 1;
}
    
sub time_keepalive {
    Danga::Socket->AddTimer( KEEPALIVE_TIMER, \&time_keepalive );
    
    bcast_event( { "time" => time() } );
    return 5;
}

sub unescape {
	my $es = shift;
	$es =~ s/([0-9a-fA-F]{2})/$hex_chr{$1}/gs;
	return $es;
}

# patch perlbal
{
    no warnings;
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

=pod

=head1 NAME

Perlbal::Plugin::ShortBus - Perlbal plugin for ShortBus

=head1 SYNOPSIS
    # perlbal.conf

    LOAD ShortBus
    LOAD vhosts

    SERVER max_connections = 10000

    CREATE POOL apache
        POOL apache ADD 127.0.0.1:81
    CREATE SERVICE apache_proxy
        SET role = reverse_proxy
        SET pool = apache
        SET persist_backend = on
        SET backend_persist_cache = 2
        SET verify_backend = on
        SET enable_reproxy = true
    ENABLE apache_proxy

    CREATE SERVICE shortbus
        SET role = reverse_proxy
        SET plugins = ShortBus
    ENABLE shortbus

    CREATE SERVICE web
        SET listen         = 10.0.0.1:80
        SET role           = selector
        SET plugins        = vhosts
        SET persist_client = on
        VHOST *.yoursite.com = apache_proxy
        VHOST *              = apache_proxy
    ENABLE web

=head1 ABSTRACT

This plugin allows Perlbal to put clients into a push type connection state.

=head1 DESCRIPTION

This Plugin works by keeping a conneciton open after an external webserver has
authorized the client.  That way, your valuable http processes can continue
servicing other clients.

The easiest way to use this module is to setup apache on port 81, and Perlbal
on port 80 as in the synopsis.  Start perbal and use a supported javascript
library.  You can find supported libraries at L<http://shortbus.xantus.org/>

=head2 ShortBus Notes

=head2 EXPORT

Nothing.

=head1 SEE ALSO

L<Perlbal>

=head1 AUTHOR

David Davis E<lt>xantus@cpan.orgE<gt>

=head1 RATING

Please rate this module. L<http://cpanratings.perl.org/rate/?distribution=Perlbal-Plugin-ShortBus>

=head1 COPYRIGHT AND LICENSE

Copyright 2006 by David Davis

This library is free software; you can redistribute it and/or modify
it under the terms of the [TODO LICENSE HERE] license.

=cut

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
