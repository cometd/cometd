#!/usr/bin/perl

use lib qw( lib easydbi-lib );

# use this before POE, so Sprocket loads the Epoll loop if we have it
use Sprocket qw(
    Client
    Server
    Plugin::HTTP::Server
    Plugin::HTTP::Deny
    Plugin::Manager
);
use POE;

my %opts = (
    LogLevel => 4,
    TimeOut => 0,
    MaxConnections => 32000,
);

# comet http server
Sprocket::Server->spawn(
    %opts,
    Name => 'HTTP Server',
    ListenPort => 8002,
    ListenAddress => '0.0.0.0',
    Plugins => [
        {
            Plugin => Sprocket::Plugin::HTTP::Server->new(
                DocumentRoot => $ENV{PWD}.'/html',
                ForwardList => {
                    qr|/\.| => 'HTTP::Deny',
                }
            ),
            Priority => 0,
        },
        {
            Plugin => Sprocket::Plugin::HTTP::Deny->new(),
            Priority => 1,
        },
    ],
);

# backend server
Sprocket::Server->spawn(
    %opts,
    Name => 'Manager',
    ListenPort => 5000,
    ListenAddress => '127.0.0.1',
    Plugins => [
        {
            Plugin => Sprocket::Plugin::Manager->new(),
            Priority => 0,
        },
    ],
);


$poe_kernel->run();

1;
