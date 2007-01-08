#!/usr/bin/perl

use lib 'lib';

use POE;
use POE::Component::Cometd::Client;
use POE::Component::Cometd::Server;
use Cometd::Plugin::JSONTransport;
use Cometd::Plugin::ChannelManager::InMemory;
use Cometd::Plugin::HTTPD;
use Cometd::Plugin::Manager;


my %opts = (
    LogLevel => 4,
    TimeOut => 0,
    MaxConnections => 32000,
);

my $chanman;

# conencts to perlbal servers that handle client connections
my $client = POE::Component::Cometd::Client->spawn(
    %opts,
    Name => 'Perlbal Connector',
    ClientList => [
#        '127.0.0.1:2022', # Perlbal Cometd manage port
    ],
    Transports => [
        {
            plugin => Cometd::Plugin::JSONTransport->new(
                chman => $chanman = Cometd::Plugin::ChannelManager::InMemory->new()
            ),
            priority => 0,
        },
    ],
);

# the channel manager needs a perlbal connector to deliver events to
$chanman->set_client( $client );

# backend server accepts connections and receives events
my $server = POE::Component::Cometd::Server->spawn(
    %opts,
    Name => 'Cometd Backend Server',
    ListenPort => 6000,
    ListenAddress => '127.0.0.1',
    Transports => [
        {
            plugin => Cometd::Plugin::JSONTransport->new(
                chman => $chanman
            ),
            priority => 0,
        },
    ],
);

# comet http server
my $http_server = POE::Component::Cometd::Server->spawn(
    %opts,
    Name => 'HTTP Server',
    ListenPort => 8080,
    ListenAddress => '0.0.0.0',
    Transports => [
        {
            plugin => Cometd::Plugin::HTTPD->new(),
            priority => 0,
        },
    ],
);

# backend server
my $manager = POE::Component::Cometd::Server->spawn(
    %opts,
    Name => 'Manager',
    ListenPort => 5000,
    ListenAddress => '127.0.0.1',
    Transports => [
        {
            plugin => Cometd::Plugin::Manager->new(),
            priority => 0,
        },
    ],
);


$poe_kernel->run();

1;
