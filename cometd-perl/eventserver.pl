#!/usr/bin/perl

use lib qw( lib easydbi-lib );

# use this before POE, so Cometd loads the Epoll loop if we have it
use POE::Component::Cometd qw( Client Server );
use POE;
use Cometd qw(
    Plugin::JSONTransport
    Plugin::EventManager::SQLite
    Plugin::HTTP
    Plugin::HTTPComet
    Plugin::Manager
    Plugin::Simple
);

my %opts = (
    LogLevel => 4,
    TimeOut => 0,
    MaxConnections => 32000,
);


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
    Name => 'HTTP Comet Server',
    ListenPort => 8001,
    ListenAddress => '0.0.0.0',
    Transports => [
        {
            Plugin => Cometd::Plugin::HTTP->new(
                DocumentRoot => $ENV{PWD}.'/html',
                ForwardList => {
                    qr|^/cometd/?| => 'HTTPComet',
                }
            ),
            Priority => 0,
        },
        {
            Plugin => Cometd::Plugin::HTTPComet->new(
                EventManager => 'eventman'
            ),
            Priority => 1,
        },
    ],
);


POE::Component::Cometd::Server->spawn(
    %opts,
    Name => 'Simple Server',
    ListenPort => 8000,
    ListenAddress => '0.0.0.0',
    Transports => [
        {
            Plugin => Cometd::Plugin::Simple->new(),
            Priority => 0,
        },
    ],
    EventManager => {
        module => 'Cometd::Plugin::EventManager::SQLite',
        options => [
            Alias => 'eventman',
            SqliteFile => 'pubsub.db',
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
            Plugin => Cometd::Plugin::Manager->new( EventManager => 'eventman' ),
            Priority => 0,
        },
    ],
);


$poe_kernel->run();

1;
