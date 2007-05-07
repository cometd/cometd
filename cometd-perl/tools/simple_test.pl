#!/usr/bin/perl

use lib qw( lib easydbi-lib );

use Sprocket qw(
    Server
    Plugin::Manager
);
use POE;

my %opts = (
    LogLevel => 4,
    TimeOut => 0,
    MaxConnections => 32000,
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
