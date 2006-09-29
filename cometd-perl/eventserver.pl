#!/usr/bin/perl

use lib './lib';

use POE;
use POE::Component::Cometd::Client;
use POE::Component::Cometd::Server;
use Cometd::Plugin::JSONTransport;
use Cometd::Plugin::ChannelManager::InMemory;

my %opts = (
    LogLevel => 4,
    TimeOut => 0,
    MaxConnections => 32000,
    Transports => {
        JSON => {
            priority => 0,
            plugin => Cometd::Plugin::JSONTransport->new(
                chman => Cometd::Plugin::ChannelManager::InMemory->new(),
            ),
        },
    },
);

# accepts connections and receives events
my $server = POE::Component::Cometd::Server->spawn(
    %opts,
    ListenPort => 6000,
    ListenAddress => '0.0.0.0',
);

# conencts to perlbal servers that handle client connections
my $client = POE::Component::Cometd::Client->spawn(
    %opts,
    ClientList => [
        '127.0.0.1:2022', # Perlbal Cometd manage port
    ],
);

$poe_kernel->run();

1;
