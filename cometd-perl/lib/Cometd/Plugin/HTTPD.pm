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

    return $self;
}

sub add_plugin {
    my $self = shift;
    
    return if ( $self->{_session_id} );
    
    # save the session id
    $self->{_session_id} =
    POE::Session->create(
        object_states =>  [
            $self => [qw(
                _start
                _stop
            )]
        ],
    )->ID();

    return undef;
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

    $con->wheel->pause_input();

#    warn Data::Dumper->Dump([$req]);

    my $r = HTTP::Response->new( 200 );
    $r->content_type( 'text/plain' );
    $r->content( 'cometd test server' );
    $con->send( $r );
    $con->close();
    return 1;

    $self->{service}->handle_request_json( $con, $req, $req );
    
    return 1;
}

1;
