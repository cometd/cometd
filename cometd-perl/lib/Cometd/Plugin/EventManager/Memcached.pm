package Cometd::Plugin::EventManager::Memcached;

use Cometd qw( Plugin::EventManager );

use base 'Cometd::Plugin::EventManager';

sub new {
    my $class = shift;
    $class->SUPER::new(
        name => 'Manager',
        @_
    );
}

sub name {
    shift->{name};
}

1;
