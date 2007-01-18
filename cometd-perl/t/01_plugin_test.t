use warnings;
use strict;

use Test::More tests => 11;

BEGIN {
    use_ok 'POE';
    use_ok 'Cometd';
    use_ok 'POE::Component::Cometd::Client';
    use_ok 'POE::Component::Cometd::Server';
    use_ok 'Cometd::Plugin::Test';
}

my %opts = (
    LogLevel => 4,
    TimeOut => 0,
#    MaxConnections => 100,
);

my $test_server = POE::Component::Cometd::Server->spawn(
    %opts,
    Name => 'Test Server',
    ListenPort => 9979,
    ListenAddress => '127.0.0.1',
    Transports => [
        {
            plugin => Cometd::Plugin::Test->new(),
        },
    ],
);

my $test_client = POE::Component::Cometd::Client->spawn(
    %opts,
    Name => 'Test Client',
    ClientList => [
        '127.0.0.1:9979', # Perlbal Cometd manage port
    ],
    Transports => [
        {
            plugin => Cometd::Plugin::Test->new(),
        },
    ],
);

$poe_kernel->run();
