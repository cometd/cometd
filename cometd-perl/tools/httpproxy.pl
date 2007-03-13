#!/usr/bin/perl

use lib qw( lib easydbi-lib );

# use this before POE, so Cometd loads the Epoll loop if we have it
use POE::Component::Cometd qw( Client Server );
use POE;
use Cometd qw(
    Plugin::HTTPProxy
);

my %opts = (
    LogLevel => 4,
    TimeOut => 0,
    MaxConnections => 32000,
);


POE::Component::Cometd::Server->spawn(
    %opts,
    Name => 'HTTP Proxy',
    ListenPort => 8080,
    ListenAddress => '0.0.0.0',
    Plugins => [
        {
            Plugin => Cometd::Plugin::HTTPProxy->new(
                AllowList => {
                    '127.0.0.1' => 1,
                },
                client => POE::Component::Cometd::Client->spawn(
                    %opts,
                    TimeOut => 20,
                    ClientAlias => 'http-proxy-client',
                    Name => 'HTTP Proxy Client',
                    Plugins => [
                        {
                            Plugin => Cometd::Plugin::HTTPProxy->new(),
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
