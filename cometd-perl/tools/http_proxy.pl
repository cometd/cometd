#!/usr/bin/perl

use lib qw( lib easydbi-lib );

# use this before POE, so Sprocket loads the Epoll loop if we have it
use Sprocket qw(
    Client
    Server
    Plugin::HTTP::Proxy
);
use POE;

my %opts = (
    LogLevel => 4,
    TimeOut => 0,
    MaxConnections => 32000,
);


Sprocket::Server->spawn(
    %opts,
    Name => 'HTTP Proxy',
    ListenPort => 8080,
    ListenAddress => '0.0.0.0',
    Plugins => [
        {
            Plugin => Sprocket::Plugin::HTTP::Proxy->new(
                AllowList => {
                    '127.0.0.1' => 1,
                },
                client => Sprocket::Client->spawn(
                    %opts,
                    TimeOut => 20,
                    ClientAlias => 'http-proxy-client',
                    Name => 'HTTP Proxy Client',
                    Plugins => [
                        {
                            Plugin => Sprocket::Plugin::HTTP::Proxy->new(),
                            Priority => 0,
                        },
                    ],
                ),
            ),
            Priority => 0,
        },
    ],
);


$poe_kernel->run();

1;
