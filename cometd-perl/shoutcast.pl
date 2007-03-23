#!/usr/bin/perl

use lib qw( lib easydbi-lib );

# use this before POE, so Sprocket loads the Epoll loop if we have it
use Sprocket qw(
    Client
    Server
    Plugin::ShoutStream
    Plugin::Manager
);
use POE;

my %opts = (
    LogLevel => 4,
    TimeOut => 0,
    MaxConnections => 32000,
);

# atom client
Sprocket::Client->spawn(
    %opts,
    Name => 'Shoutcast',
    Plugins => [
        {
            Plugin => Sprocket::Plugin::ShoutStream->new(
                StreamList => [
                    'http://uvox1-ntc-slot3l.stream.aol.com/stream/1013',
#                    'http://216.66.69.246:8070/',
                ],
            ),
            Priority => 0,
        },
    ],
);

Sprocket::Server->spawn(
    %opts,
    Name => 'Manager',
    ListenPort => 5001,
    ListenAddress => '127.0.0.1',
    Plugins => [
        {
            Plugin => Sprocket::Plugin::Manager->new( Alias => 'eventman' ),
            Priority => 0,
        },
    ],
);


$poe_kernel->run();

1;
