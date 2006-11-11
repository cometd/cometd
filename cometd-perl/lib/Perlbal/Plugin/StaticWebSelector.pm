package Perlbal::Plugin::StaticWebSelector;

use strict;

sub register {
    my $svc = $_[1];
    die "This must be used with selector service."
        unless ( $svc && $svc->{role} eq "selector" );
    $svc->selector( \&selector );
    return 1;
}

sub unregister {
    my $svc = $_[1];
    $svc->selector(undef);
    return 1;
}

sub load   { return 1 }

sub unload { return 1 }

sub selector {
    my Perlbal::ClientHTTPBase $client = shift;
    my Perlbal::HTTPHeaders $hd = $client->{req_headers}
        or return $client->_simple_response(404, "Not Found");

    my $uri = $hd->request_uri;

    my $svc = Perlbal->service(
        ( $uri =~ m~^/cometd~ ) ? 'apache_proxy' : 'static'
    );

    unless ($svc) {
        $client->_simple_response( 404, 'Not found - no service for this uri' );
        return 1;
    }

    $svc->adopt_base_client($client);
    return 1;
}

1;

=pod

   # In perlbal.conf:

   LOAD StaticWebSelector
   
   CREATE SERVICE selector
        SET role = selector
        SET listen = 0.0.0.0:80
        SET plugins = staticwebselector
        SET persist_client = on
   ENABLE selector

   # name your web proxy: web_proxy, and your web_server: static_server

=cut
