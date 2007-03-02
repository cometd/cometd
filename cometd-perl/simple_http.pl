#!/usr/bin/perl

use lib qw( lib easydbi-lib );

# use this before POE, so Cometd loads the Epoll loop if we have it
use POE::Component::Cometd qw( Client Server );
use POE;
use Cometd qw(
    Plugin::HTTP::Server
    Plugin::HTTP::Deny
    Plugin::Manager
);

my %opts = (
    LogLevel => 4,
    TimeOut => 0,
    MaxConnections => 32000,
);

# comet http server
POE::Component::Cometd::Server->spawn(
    %opts,
    Name => 'HTTP Server',
    ListenPort => 8001,
    ListenAddress => '0.0.0.0',
    Transports => [
        {
            Plugin => Cometd::Plugin::HTTP::Server->new(
                DocumentRoot => $ENV{PWD}.'/html',
                ForwardList => {
                    qr|/\.| => 'HTTP::Deny',
                }
            ),
            Priority => 0,
        },
        {
            Plugin => Cometd::Plugin::HTTP::Deny->new(),
            Priority => 1,
        },
    ],
);

# backend server
POE::Component::Cometd::Server->spawn(
    %opts,
    Name => 'Manager',
    ListenPort => 5000,
    ListenAddress => '127.0.0.1',
    Transports => [
        {
            Plugin => Cometd::Plugin::Manager->new(),
            Priority => 0,
        },
    ],
);


$poe_kernel->run();

1;
