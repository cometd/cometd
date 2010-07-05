#!/usr/bin/perl

use lib 'lib';

# use this before POE, so Sprocket loads the Epoll loop if we have it
use Sprocket qw(
    Client
    Server
    Plugin::Manager
    Plugin::Memcached
);
use POE;

my %opts = (
    LogLevel => 4,
    TimeOut => 0,
    MaxConnections => 32000,
);


POE::Session->create(
    package_states => [
        main => [qw(
            _start

            memcached_connected
            memcached_receive
        )]
    ]
);

sub _start {

    my $m = $_[HEAP] = Sprocket::Plugin::Memcached->new(
        listener => $_[SESSION]->ID(),
        event_connected => 'memcached_connected',
        event_receive => 'memcached_receive',
    );
    
    # conencts to perlbal servers that handle client connections
    Sprocket::Client->spawn(
        %opts,
        Name => 'Memcached 1',
        ClientList => [
            '127.0.0.1:11211',
        ],
        Transports => [
            {
                plugin => $m,
                priority => 0,
            },
        ],
    );
    
    # backend server
    Sprocket::Server->spawn(
        %opts,
        Name => 'Manager',
        ListenPort => 5000,
        ListenAddress => '127.0.0.1',
        Transports => [
            {
                plugin => Sprocket::Plugin::Manager->new(),
                priority => 0,
            },
        ],
    );

    return;
}

sub memcached_connected {
    my $heap = @_[HEAP];
    
    $heap->version({ callback => sub { my ($i,$v) = @_; warn "memcached version $v->{version}"; } });

    $heap->set( { key => 'foo2', obj => 'bar test' } );
    
    $heap->add( { key => 'foo1111', obj => 'bar test!!!111one' } );

    $heap->get( { key => 'foo2' } );
    
    $heap->replace( { key => 'foo2', obj => 'baz' } );
    
    $heap->add( { key => 'foo1111', obj => 'should fail' } );
    
    $heap->delete( { key => 'foo2' } );
    
    $heap->get( { key => 'foo2' } );
    
    $heap->set( { key => 'foo2', obj => 'foobar' } );
    
    $heap->set( { key => 'foo3', obj => 12 } );
    
    $heap->incr( { key => 'foo3' } );
    
    $heap->decr( { key => 'foo3', by => 2 } );
    
    $heap->get( { keys => [ qw( foo2 foo3 foo2 foo ) ] } );
    
    $heap->flush_all();
}

sub memcached_receive {
    warn "recv: ".Data::Dumper->Dump([@_[ARG0, ARG1]]);
}

$poe_kernel->run();

1;
