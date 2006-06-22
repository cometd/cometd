#!/usr/bin/perl

use lib qw( lib );

use POE qw( Component::Server::ShortBus );

POE::Component::Server::ShortBus->spawn(
    LogLevel => 2,
    TimeOut => 0,
    MaxConnections => 30000,
    ClientList => [
        (map { '127.0.0.1:6000' } ( 1 .. 5000 ))
    ]
);

$poe_kernel->run();

1;
