#!/usr/bin/perl

use lib qw( lib easydbi-lib );

# use this before POE, so Sprocket loads the Epoll loop if we have it
use Sprocket qw(
    Server
    Client
    Plugin::AtomStream
    Plugin::Manager
    Plugin::Simple
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
    Name => 'Updates SixApart',
    ClientList => [
        'updates.sixapart.com:80',
    ],
    Plugins => [
        {
            Plugin => Sprocket::Plugin::AtomStream->new(
                FeedChannel => '/sixapart/atom',
                EventManager => 'eventman',
            ),
            Priority => 0,
        },
    ],
);

Sprocket::Server->spawn(
    %opts,
    Name => 'Simple Server',
    ListenPort => 8000,
    ListenAddress => '0.0.0.0',
    Plugins => [
        {
            Plugin => Sprocket::Plugin::Simple->new(
                DefaultChannel => '/sixapart/atom',
                EventManager => 'eventman',
            ),
            Priority => 0,
        },
    ],
    EventManager => {
        module => 'Sprocket::Plugin::EventManager::SQLite',
        options => [
            Alias => 'eventman',
            SqliteFile => 'pubsub.db',
#            SqliteFile => '',
        ]
    },
);

Sprocket::Client->spawn(
    %opts,
    Name => 'Updates SixApart',
    ClientList => [
        '127.0.0.1:8000',
        '127.0.0.1:8000',
    ],
    Plugins => [
        {
            Plugin => Sprocket::Plugin::Simple->new(
                EventManager => 'eventman',
            ),
            Priority => 0,
        },
    ],
);

use Data::Dumper;
POE::Session->create(
    inline_states => {
        _start => sub {
            $_[KERNEL]->alias_set( "debug_logger" );
        },
        _log => sub {
#            $svr->_log( v => 4, msg => Data::Dumper->Dump([ $_[ ARG0 ] ]) );
        }
    }
);

Sprocket::Server->spawn(
    %opts,
    Name => 'Manager',
    ListenPort => 5000,
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
