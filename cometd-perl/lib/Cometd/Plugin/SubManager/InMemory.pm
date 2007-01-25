package Cometd::Plugin::SubManager::InMemory;
# simple in memory channel manager

use strict;
use warnings;

use base qw( Cometd::Plugin::SubManager );

sub new {
    my $class = shift;
    $class->SUPER::new( @_ );
}


1;
