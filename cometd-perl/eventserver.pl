#!/usr/bin/perl

use lib qw( lib sprocket-lib poe-filter-json-lib easydbi-lib );

# use this before POE, so Sprocket loads the Epoll loop if we have it
use Sprocket qw(
    Client
    Server
    Plugin::JSONTransport
    Plugin::EventManager::SQLite
    Plugin::HTTP::Server
    Plugin::HTTP::Deny
    Plugin::HTTP::CGI
    Plugin::HTTP::AlwaysModified
    Plugin::HTTP::Comet
    Plugin::Manager
    Plugin::AtomStream
    Plugin::Simple
);
use POE;

my %opts = (
    LogLevel => 4,
    TimeOut => 0,
    MaxConnections => 32000,
);


# backend server accepts connections and receives events
Sprocket::Server->spawn(
    %opts,
    Name => 'Sprocket Backend Server',
    ListenPort => 6000,
    ListenAddress => '127.0.0.1',
    Plugins => [
        {
            Plugin => Sprocket::Plugin::JSONTransport->new(
                EventManager => 'eventman'
            ),
            Priority => 0,
        },
    ],
);

# comet http server
Sprocket::Server->spawn(
    %opts,
    Name => 'HTTP Comet Server',
    ListenPort => ( $ENV{USER} eq 'root' ? 80 : 8001 ),
    ListenAddress => '0.0.0.0',
    Plugins => [
        {
            Plugin => Sprocket::Plugin::HTTP::Server->new(
                DocumentRoot => $ENV{PWD}.'/html',
                ForwardList => {
                    # deny any files or dirs access beginning with .
                    qr|/\.| => 'HTTP::Deny',
                    qr/\.(pl|cgi)$/ => 'HTTP::CGI',
                    # forward /cometd to the Comet plugin
                    qr|^/cometd/?$| => 'HTTP::Comet',
                }
            ),
            Priority => 0,
        },
        {
            Plugin => Sprocket::Plugin::HTTP::Comet->new(
                EventManager => 'eventman'
            ),
            Priority => 1,
        },
        {
            Plugin => Sprocket::Plugin::HTTP::Deny->new(),
            Priority => 2,
        },
        {
            Plugin => Sprocket::Plugin::HTTP::CGI->new(),
            Priority => 3,
        },
        {
            Plugin => Sprocket::Plugin::HTTP::AlwaysModified->new(),
            Priority => 4,
        },
    ],
    EventManager => {
        module => 'Sprocket::Plugin::EventManager::SQLite',
        options => [
            Alias => 'eventman',
            SqliteFile => 'pubsub.db',
        ]
    },
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
);

# backend server
my $svr = Sprocket::Server->spawn(
    %opts,
    Name => 'Manager',
    ListenPort => 5000,
    ListenAddress => '127.0.0.1',
    Plugins => [
        {
            Plugin => Sprocket::Plugin::Manager->new( EventManager => 'eventman' ),
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
            $svr->_log( v => 4, msg => Data::Dumper->Dump([ $_[ ARG0 ] ]) );
        }
    }
);

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


$poe_kernel->run();

1;
