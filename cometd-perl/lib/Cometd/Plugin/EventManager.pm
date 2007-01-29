package Cometd::Plugin::EventManager;

use Cometd qw( Plugin );
use base 'Cometd::Plugin';
use POE;

use strict;
use warnings;

sub new {
    my $class = shift;
    $class->SUPER::new(
        @_
    );
}

sub _log {
    $poe_kernel->call( shift->parent_id => _log => @_ );
}

1;
