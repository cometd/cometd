use warnings;
use strict;

use Test::More tests => 10;

BEGIN {
    use_ok 'POE';
    use_ok 'Sprocket';
    use_ok 'Sprocket::Client';
    use_ok 'Sprocket::Server';
}

my %opts = (
    LogLevel => 4,
    TimeOut => 0,
);

my $test_server = Sprocket::Server->spawn(
    %opts,
    Name => 'Test Server',
    ListenPort => 9979,
    ListenAddress => '127.0.0.1',
    Plugins => [
        {
            plugin => Sprocket::Plugin::Test->new(),
        },
    ],
);

my $test_client = Sprocket::Client->spawn(
    %opts,
    Name => 'Test Client',
    ClientList => [
        '127.0.0.1:9979',
    ],
    Plugins => [
        {
            plugin => Sprocket::Plugin::Test->new(),
        },
    ],
);

$poe_kernel->run();


package Sprocket::Plugin::Test;

use Sprocket qw( Plugin );
use base 'Sprocket::Plugin';

use POE::Filter::Line;

use strict;
use warnings;

sub new {
    my $class = shift;
    $class->SUPER::new(
        name => 'Test',
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
    
    $self->take_connection( $con );
    # POE::Filter::Stackable object:
    $con->filter->push( POE::Filter::Line->new() );
    Test::More::pass("connected, sending test");
    
    $con->send( "Test!" );

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
    Test::More::pass("local disconnected");
    $server->shutdown();
}

# ---------------------------------------------------------
# client

sub remote_connected {
    my ( $self, $client, $con, $socket ) = @_;

    $self->take_connection( $con );
    # POE::Filter::Stackable object:
    $con->filter->push( POE::Filter::Line->new() );
    
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
    Test::More::fail("remote connect timeout");
}

sub remote_connect_error {
    Test::More::fail("remote connect error");
}

sub remote_error {
    Test::More::fail("remote error");
}

1;
