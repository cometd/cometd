package Cometd::Plugin::ChannelManager::InMemory;
# simple in memory channel manager

use strict;
use warnings;

use base qw( Cometd::Plugin::ChannelManager );

sub new {
    my $class = shift;
    $class->SUPER::new( @_ );
}


1;
