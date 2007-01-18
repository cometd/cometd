package Cometd::Plugin::Test;

# used for tests in t/

use Cometd qw( Plugin );
use base 'Cometd::Plugin';

use POE::Filter::Line;

use strict;
use warnings;

sub new {
    my $class = shift;
    $class->SUPER::new(
        plugin_name => 'Test',
        @_
    );
}

sub as_string {
    __PACKAGE__;
}

# ---------------------------------------------------------
# server

sub local_connected {
    my ( $self, $server, $con, $socket ) = @_;
    
    if ( my $wheel = $con->wheel ) {
        $con->transport( $self->plugin_name );
        # input_filter and output_filter are the same
        # POE::Filter::Stackable object:
        $wheel->get_input_filter->push(
            POE::Filter::Line->new()
        );
        Test::More::pass("connected, sending test");
    
        $con->send( "Test!" );
    }

    return 1;
}

sub local_receive {
    my ( $self, $server, $con, $data ) = @_;
    
    if ( $data =~ m/^Test!/i ) {
        $con->send( "quit" );
        Test::More::pass("received test, sending quit");
    } elsif ( $data =~ m/^quit/i ) {
        $con->send( "goodbye." );
        Test::More::pass("received quit, closing connection");
        $con->close();
    }
    
    return 1;
}

sub local_disconnected {
    my ( $self, $server, $con ) = @_;
    $server->shutdown();
    Test::More::pass("local disconnected");
}

# ---------------------------------------------------------
# client

sub remote_connected {
    my ( $self, $client, $con, $socket ) = @_;

    if ( my $wheel = $con->wheel ) {
        $con->transport( $self->plugin_name );
        # input_filter and output_filter are the same
        # POE::Filter::Stackable object:
        $wheel->get_input_filter->push(
            POE::Filter::Line->new()
        );
    }

    return 1;
}

sub remote_receive {
    my ( $self, $client, $con, $data ) = @_;
    
    if ( $data =~ m/^Test!/i ) {
        Test::More::pass("received test, sending test");
        $con->send( "Test!" );
    } elsif ( $data =~ m/^quit/i ) {
        Test::More::pass("received quit, closing connection");
        #$con->send( "quit" );
        $con->close();
    }
}

sub remote_disconnected {
    my ( $self, $client, $con ) = @_;
    Test::More::pass("remote disconnected");
    $client->shutdown();
}

sub remote_connect_timeout {
    warn "remote connect timeout";
}

sub remote_connect_error {
    warn "remote connect error";
}

sub remote_error {
    warn "remote error";
}

1;
