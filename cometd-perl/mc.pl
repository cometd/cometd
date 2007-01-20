#!/usr/bin/perl

use lib 'lib';

# use this before POE, so Cometd loads the Epoll loop if we have it
use POE::Component::Cometd qw( Client Server );
use POE;
use Cometd qw(
    Plugin::Manager
    Plugin::Memcached
);

my %opts = (
    LogLevel => 4,
    TimeOut => 0,
    MaxConnections => 32000,
);

# conencts to perlbal servers that handle client connections
my $c1 = POE::Component::Cometd::Client->spawn(
    %opts,
    Name => 'Memcached 1',
    ClientList => [
        '127.0.0.1:11211',
    ],
    Transports => [
        {
            plugin => Cometd::Plugin::Memcached->new(),
            priority => 0,
        },
    ],
);

# backend server
my $manager = POE::Component::Cometd::Server->spawn(
    %opts,
    Name => 'Manager',
    ListenPort => 5000,
    ListenAddress => '127.0.0.1',
    Transports => [
        {
            plugin => Cometd::Plugin::Manager->new(),
            priority => 0,
        },
    ],
);


$poe_kernel->run();

1;
