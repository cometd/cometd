#!/usr/bin/perl

use lib qw( lib sprocket-lib easydbi-lib );

# use this before POE, so Sprocket loads the Epoll loop if we have it
use Sprocket qw(
    Server
    Plugin::Manager
    Plugin::HTTP::Server::DAV
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
    Name => 'DAV Server',
    ListenPort => 4242,
    ListenAddress => '0.0.0.0',
    Plugins => [
        {
            Plugin => Sprocket::Plugin::HTTP::Server::DAV->new(
                DocumentRoot => $ENV{PWD}.'/html/test/tmp',
            ),
            Priority => 0,
        }
    ],
);

Sprocket::Server->spawn(
    %opts,
    Name => 'Manager',
    ListenPort => 5001,
    ListenAddress => '127.0.0.1',
    Plugins => [
        {
            Plugin =>Sprocket::Plugin::Manager->new(),
            Priority => 0,
        },
    ],
);

$poe_kernel->run();

1;
