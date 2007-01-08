package Cometd::Plugin::HTTPD;

use Cometd::Plugin;
use base 'Cometd::Plugin';

use Cometd::Service::HTTPD;

use POE::Filter::HTTPD;
use HTTP::Response;

use strict;
use warnings;

use constant NAME => 'HTTPD';

sub new {
    my $class = shift;
    bless({
        service => Cometd::Service::HTTPD->new(),
        @_
    }, $class);
}

sub as_string {
    __PACKAGE__;
}

sub plugin_name {
    NAME;
}


# ---------------------------------------------------------
# server

sub local_connected {
    my ( $self, $server, $con, $socket ) = @_;
    $con->transport( NAME );
    if ( my $wheel = $con->wheel ) {
        # POE::Filter::Stackable object:
        $wheel->get_input_filter->push(
            POE::Filter::HTTPD->new()
        );
        
        # XXX should we pop the stream filter off the top?
    }
    return 1;
}

sub local_receive {
    my ( $self, $server, $con, $data ) = @_;
#    $self->{service}->handle_request( $self, $con, $data );
#    return;
#    warn Data::Dumper->Dump([$data]);
    my $r = HTTP::Response->new( 200 );
    $r->content_type( 'text/plain' );
    $r->content( 'cometd test server' );
    $con->send( $r );
    $con->close_on_flush( 1 );
    return 1;
}

1;
