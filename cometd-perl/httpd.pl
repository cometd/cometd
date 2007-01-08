#!/usr/bin/perl

use strict;
use warnings;

use lib qw(lib);

use POE qw( Component::Server::HTTPServer );

our $home = "./html";

POE::Session->create(
    inline_states => {
        _start => sub {
            my $server = $_[HEAP]->{server} = POE::Component::Server::HTTPServer->new();
            $server->port( 8021 );
            $server->handlers( [
                '/' => new_handler( StaticHandler => $home ),
                '/cometd' => MyHandler->new(),
            ] );
            $_[HEAP]->{svc} = $server->create_server();
        },
    }
);

$poe_kernel->run();

1;

package MyHandler;

# import constants
use POE::Component::Server::HTTPServer::Handler;
# subclass it
use base 'POE::Component::Server::HTTPServer::Handler';
use Data::GUID;
use HTTP::Cookies;
use URI::Escape;
use JSON;

sub handle {
    my $self = shift;
    my $context = shift;
    my $r = $context->{response};
    my $s = $context->{request};

    require Data::Dumper;
    warn Data::Dumper->Dump([$s]);

    my $guid;
    if ($s->header('Cookie')) {
        ($guid) = ( $s->header( 'Cookie' ) =~ m/CID=(.*)/ );
        warn "cookie in:".$s->header('Cookie');
    }
    if (!$guid) {
        $guid = Data::GUID->new();
    }

    unless ($s->header('Host')) {
        $r->code(500);
        $r->content('Invalid request');
        return H_FINAL;
    }
    
    my ($domain,$port) = ( $s->header('Host') =~ m/([^:]+)(?::(\d+))?/ );
    
    if ( $domain !~ m/^\d{1,3}\.\d{1,3}\.\d{1,3}/ ) {
        # remove first part of domain
        $domain =~ s/^[^\.]+\.//;
    }

    # message=%5B%7B%22version%22%3A0.1%2C%22minimumVersion%22%3A0.1%2C%22channel%22%3A%22/meta/handshake%22%7D%5D
    
    my %in;
    my ( $uri ) = ( $s->content ) ? $s->content : ( $s->uri =~ m/(message=.*)/ );
    foreach ( split ( /&/, $uri ) ) {
        my ($k, $v) = split(/=/);
        $in{$k} = uri_unescape($v);
    }

    my @ch = qw( /pub/foo );

    if ( $in{message} ) {
        # [{"version":0.1,"minimumVersion":0.1,"channel":"/meta/handshake"}]
        print "$in{message}\n";
        if ( $in{message} =~ m/^\[/ ) {
            my $o = eval { jsonToObj($in{message}); };
            if ($@) {
                warn $@;
            }
            if (ref($o) eq 'ARRAY') {
                foreach my $m (@$o) {
                    next unless (ref($m) eq 'HASH');
                    if ($m->{clientId}) {
                        $guid = $m->{clientId};
                        print "located client id $guid\n";
                    }
                    if ($m->{channel}) {
                        next if ($m->{channel} =~ m~/meta/~i);
                        push(@ch,$m->{channel});
                    }
                }
            }
        }
    }
    
    my @sb = ( "id=$guid", "domain=$domain", "channels=".join(';',@ch), "action=connect" );
    
    my ( $qry ) = ( $s->uri =~ m/\?(.*)/ );
    if ( $qry ) {
        foreach ( split ( /&/, $qry ) ) {
            my ( $k, $v ) = split( /=/ );
            $in{$k} = uri_unescape($v);
        }
        if ( $in{tunnelInit} ) {
            push( @sb, "tunnelType=".uri_escape( $in{tunnelInit} ) );
        }
    }
    
    $r->code( 200 );
    $r->header( 'X-REPROXY-SERVICE' => 'cometd' );
    $r->header( 'X-COMETD' => join('; ',@sb ) );
    $r->header( 'X-COMETD-DATA' => uri_escape($in{message}) )
        if ( $in{message} );
    
    warn "x-cometd:".join('; ',@sb );
    
    my $c = HTTP::Cookies->new( {} );
    $c->set_cookie( 0, 'CID', "$guid", '/',
        $domain, undef, undef, undef, ( 60*60*24*365 ), 0 );
    my $co = $c->as_string();
    $co =~ s/(Set-Cookie)3/$1/g; # why doesn't Set-Cookie3 work?
    $r->header( split( ': ',$co ) );
    
    warn "cookie out: $co";
    
    $r->content( "OK" );
    $r->content_type( 'text/html' );
    
    return H_FINAL;
}

1;

