#!/usr/bin/perl

use lib qw( lib );

use POE;
use POE::Component::ShortBus::Client;
use POE::Component::ShortBus::Server;
use ShortBus::Plugin::JSONTransport;

my %opts = (
    LogLevel => 2,
    TimeOut => 0,
    MaxConnections => 32000,
    Transports => {
        JSON => {
            priority => 0,
            plugin => ShortBus::Plugin::JSONTransport->new(),
        },
    },
);

my $server = POE::Component::ShortBus::Server->spawn(
    %opts,
    ListenPort => 6000,
);


POE::Component::ShortBus::Client->spawn(
    %opts,
    ClientList => [
        '127.0.0.1:6000',
    ],
);

$poe_kernel->run();

1;
