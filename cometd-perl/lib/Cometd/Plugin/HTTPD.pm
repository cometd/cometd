package Cometd::Plugin::HTTPD;

use Cometd::Plugin;
use base 'Cometd::Plugin';

use POE::Filter::HTTPD;
use HTTP::Response;

use strict;
use warnings;

sub new {
    my $class = shift;
    bless({
        @_
    }, $class);
}

sub as_string {
    __PACKAGE__;
}

# ---------------------------------------------------------
# server

sub local_connected {
    my ( $self, $server, $con, $socket ) = @_;
    $con->transport( 'HTTPD' );
    if ( $con->wheel ) {
        # POE::Filter::Stackable object:
        my $filter = $con->wheel->[ POE::Wheel::ReadWrite::FILTER_INPUT ];
        
        $filter->push(
            POE::Filter::HTTPD->new(),
        );
        
        # XXX should we pop the stream filter off the top?
    }
    return;
}

sub local_receive {
    my ($self, $server, $con, $data) = @_;
#    warn Data::Dumper->Dump([$data]);
    my $r = HTTP::Response->new( 200 );
    $r->content_type( 'text/plain' );
    $r->content( 'cometd test server' );
    $con->send( $r );
    $con->close_on_flush( 1 );
    return;
}

1;
