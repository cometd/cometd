#!/usr/bin/perl

use lib qw( lib easydbi-lib );

# use this before POE, so Cometd loads the Epoll loop if we have it
use POE::Component::Cometd qw( Server Client );
use POE;
use Cometd qw(
    Plugin::AtomStream
    Plugin::Manager
    Plugin::Simple
);

my %opts = (
    LogLevel => 4,
    TimeOut => 0,
    MaxConnections => 32000,
);

# atom client
POE::Component::Cometd::Client->spawn(
    %opts,
    Name => 'Updates SixApart',
    ClientList => [
        'updates.sixapart.com:80',
    ],
    Transports => [
        {
            Plugin => Cometd::Plugin::AtomStream->new(
                FeedChannel => '/sixapart/atom',
                EventManager => 'eventman',
            ),
            Priority => 0,
        },
    ],
);

my $svr = POE::Component::Cometd::Server->spawn(
    %opts,
    Name => 'Simple Server',
    ListenPort => 8000,
    ListenAddress => '0.0.0.0',
    Transports => [
        {
            Plugin => Cometd::Plugin::Simple->new(
                DefaultChannel => '/sixapart/atom',
                EventManager => 'eventman',
            ),
            Priority => 0,
        },
    ],
    EventManager => {
        module => 'Cometd::Plugin::EventManager::SQLite',
        options => [
            Alias => 'eventman',
            SqliteFile => 'pubsub.db',
#            SqliteFile => '',
        ]
    },
);

my $cli = POE::Component::Cometd::Client->spawn(
    %opts,
    Name => 'Updates SixApart',
    ClientList => [
        '127.0.0.1:8000',
        '127.0.0.1:8000',
    ],
    Transports => [
        {
            Plugin => Cometd::Plugin::Simple->new(
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
            $_[KERNEL]->delay_set( _log => 10 );
        },
        _log => sub {
            $_[KERNEL]->delay_set( _log => 10 );
            $cli->{total_count} += $cli->{count};
#            $svr->_log( v => 4, msg => Data::Dumper->Dump([ $_[ ARG0 ] ]) );
            $svr->_log( v => 4, msg => "count:".$cli->{total_count}." per sec:".($cli->{count} / 10) );
            $cli->{count} = 0;
        }
    }
);



# backend server
POE::Component::Cometd::Server->spawn(
    %opts,
    Name => 'Manager',
    ListenPort => 5000,
    ListenAddress => '127.0.0.1',
    Transports => [
        {
            Plugin => Cometd::Plugin::Manager->new( Alias => 'eventman' ),
            Priority => 0,
        },
    ],
);


$poe_kernel->run();

1;
