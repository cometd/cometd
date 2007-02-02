#!/usr/bin/perl

use lib 'lib';

# use this before POE, so Cometd loads the Epoll loop if we have it
use POE::Component::Cometd qw( Server Client );
use POE;
use Cometd qw(
    Plugin::AtomStream
    Plugin::Manager
);

my %opts = (
    LogLevel => 4,
    TimeOut => 0,
    MaxConnections => 32000,
);

# backend server
POE::Component::Cometd::Client->spawn(
    %opts,
    Name => 'Updates SixApart',
    ClientList => [
        'updates.sixapart.com:80',
    ],
    Transports => [
        {
            Plugin => Cometd::Plugin::AtomStream->new(),
            Priority => 0,
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
