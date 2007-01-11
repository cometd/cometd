package Cometd::Plugin::JSONTransport;

use Cometd::Plugin;
use base 'Cometd::Plugin';

use POE::Filter::Line;
use POE::Filter::JSON;

use strict;
use warnings;

use constant BAYEUX_VERSION => '1.0';

sub new {
    my $class = shift;
    $class->SUPER::new(
        plugin_name => 'JSON',
        @_
    );
}

sub as_string {
    __PACKAGE__;
}

# ---------------------------------------------------------
# Client

sub remote_connected {
    my ( $self, $client, $con, $socket ) = @_;
    $con->transport( $self->plugin_name );
    if ( my $wheel = $con->wheel ) {
        # POE::Filter::Stackable object:
        my $filter = $wheel->get_input_filter;
        
        $filter->push(
            POE::Filter::Line->new()
        );
        
        $wheel->put( "bayeux " . BAYEUX_VERSION );
        
        $filter->push(
            POE::Filter::JSON->new()
        );
        
        # XXX should we pop the stream filter off the top?
    }
    return 1;
}

sub remote_receive {
    my ($self, $client, $con, $event) = @_;
    $self->{chman}->deliver($con, $event);
    return 1;
}


# ---------------------------------------------------------
# server

sub local_connected {
    my ( $self, $server, $con, $socket ) = @_;

    $con->transport( $self->{pluigin_name} );
    
    if ( my $wheel = $con->wheel ) {
        # POE::Filter::Stackable object
        $wheel->get_input_filter->push(
            POE::Filter::Line->new(),
            POE::Filter::JSON->new(),
        );
    
        # XXX $con->send?
        $wheel->put({ bayeux => BAYEUX_VERSION });
    
        # XXX should we pop the stream filter off the top?
    }
    return 1;
}

sub local_receive {
    my ($self, $server, $con, $event) = @_;
    $self->{chman}->deliver($con, $event);
    return 1;
}

1;
