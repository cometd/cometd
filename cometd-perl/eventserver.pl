#!/usr/bin/perl

use lib 'lib';

# use this before POE, so Cometd loads the Epoll loop if we have it
use POE::Component::Cometd qw( Client Server );
use POE;
use Cometd qw(
    Plugin::JSONTransport
    Plugin::SubManager::InMemory
    Plugin::SubManager::Memcached
    Plugin::HTTPD
    Plugin::Manager
);

my %opts = (
    LogLevel => 4,
    TimeOut => 0,
    MaxConnections => 32000,
);

my $chanman;

# conencts to perlbal servers that handle client connections
POE::Component::Cometd::Client->spawn(
    %opts,
    Name => 'Perlbal Connector',
    ClientList => [
#        '127.0.0.1:2022', # Perlbal Cometd manage port
    ],
    SubManager => $chanman = Cometd::Plugin::SubManager::InMemory->new(),
    Transports => [
        {
            plugin => Cometd::Plugin::JSONTransport->new(),
            priority => 0,
        },
    ],
);

# FIXME $chanman
# backend server accepts connections and receives events
POE::Component::Cometd::Server->spawn(
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
POE::Component::Cometd::Server->spawn(
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
    SubManager => Cometd::Plugin::SubManager::Memcached->new(),
);

# backend server
POE::Component::Cometd::Server->spawn(
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
