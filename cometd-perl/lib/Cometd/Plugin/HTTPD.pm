package Cometd::Plugin::HTTPD;

use Cometd::Plugin;
use base 'Cometd::Plugin';

use Cometd::Service::HTTPD;

use POE qw( Filter::HTTPD );
use HTTP::Response;

use strict;
use warnings;

sub new {
    my $class = shift;
    
    my $self = $class->SUPER::new(
        plugin_name => 'HTTPD',
        service => Cometd::Service::HTTPD->new(),
        @_
    );

    $self->{session_id} =
    POE::Session->create(
#       options => { trace => 1 },
        object_states => [
            $self => [qw(
                _start
                _stop
            )]
        ],
    )->ID();

    return $self;
}

sub as_string {
    __PACKAGE__;
}

sub _start {
    my $self = $_[OBJECT];
    $_[KERNEL]->alias_set( "$self" );
    $self->_log(v => 1, msg => 'started');
}

sub _stop {
    my $self = $_[OBJECT];
    $self->_log(v => 1, msg => 'stopped');
}

# ---------------------------------------------------------
# server

sub local_connected {
    my ( $self, $server, $con, $socket ) = @_;
    $con->transport( $self->plugin_name );
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
    my ( $self, $server, $con, $req ) = @_;
#    warn Data::Dumper->Dump([$req]);
    my $r;
    my $c = $req->content;
    SWITCH: {
        if ( $c && $c =~ m/^\[/ ) {
            my $obj = eval { jsonToObj( $c ) };
            if ( $@ ) {
                $server->_log(v => 1, msg => "error parsing json: $@");
            } else {
                $self->{service}->handle_request_event( $self, $con, $req, $obj );
                last SWITCH;
            }
        }
        $r = HTTP::Response->new( 500 );
        $r->content( 'Server Error -  Incorrect JSON format' );
    }
    
    $r = HTTP::Response->new( 200 )
        unless($r);
    $r->content_type( 'text/plain' )
        unless($r->content_type);
    $r->content( 'cometd test server' )
        unless($r->content);
    $con->send( $r );
    $con->close_on_flush( 1 );
    return 1;
}

1;
