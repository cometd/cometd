#!/usr/bin/perl

use lib 'lib';

# use this before POE, so Cometd loads the Epoll loop if we have it
use POE::Component::Cometd qw( Client Server );
use POE;
use Cometd qw(
    Plugin::JSONTransport
    Plugin::EventManager::EasyDBI
    Plugin::HTTPD
    Plugin::Manager
);

my %opts = (
    LogLevel => 4,
    TimeOut => 0,
    MaxConnections => 32000,
);

# conencts to perlbal servers that handle client connections
{
last;
POE::Component::Cometd::Client->spawn(
    %opts,
    Name => 'Perlbal Connector',
    ClientList => [
#        '127.0.0.1:2022', # Perlbal Cometd manage port
    ],
#    EventManager => Cometd::Plugin::EventManager::InMemory->new(
#        Alias => 'eventman-inmemory'
#    ),
    Transports => [
        {
            Plugin => Cometd::Plugin::JSONTransport->new(),
            Priority => 0,
        },
    ],
);
}

# backend server accepts connections and receives events
POE::Component::Cometd::Server->spawn(
    %opts,
    Name => 'Cometd Backend Server',
    ListenPort => 6000,
    ListenAddress => '127.0.0.1',
    Transports => [
        {
            Plugin => Cometd::Plugin::JSONTransport->new(
                EventManager => 'eventman'
            ),
            Priority => 0,
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
            Plugin => Cometd::Plugin::HTTPD->new(),
            Priority => 0,
        },
    ],
    EventManager => {
        module => 'Cometd::Plugin::EventManager::EasyDBI',
        options => [
            Alias => 'eventman',
#            SqliteFile => 'cometd.com',
        ]
    },
);

# backend server
POE::Component::Cometd::Server->spawn(
    %opts,
    Name => 'Manager',
    ListenPort => 5000,
    ListenAddress => '127.0.0.1',
    Transports => [
        {
            Plugin => Cometd::Plugin::Manager->new( Alias => 'eventman' ),
            Priority => 0,
        },
    ],
);


$poe_kernel->run();

1;
