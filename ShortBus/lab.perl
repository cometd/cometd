#!/usr/bin/perl
# $Id$
use warnings;
use strict;

#our $VERSION;
#$VERSION = do {my($r)=(q$Revision$=~/(\d+)/);sprintf"1.%04d",$r};

use lib "./lab";

use POE qw( Component::ShortBus );

POE::Session->create(
    inline_states => {
        _start => \&start,
        _default => \&trace_unknown,
    
        created => \&queue_created,
    },
);

$poe_kernel->run();

sub start {
    my ( $kernel, $heap, $session ) = @_[ KERNEL, HEAP, SESSION ];

    my $bus = $heap->{b} = POE::Component::ShortBus->new();

    $kernel->refcount_increment($bus->ID, "shortbus");
    
    $bus->connect();
    
    $bus->create($session->postback("created"));
}

sub queue_created {
    my ( $kernel, $heap, $id ) = @_[ KERNEL, HEAP, ARG0 ];
    
    warn "queue created: @$id";
}

sub trace_unknown {
    my ( $event, $args ) = @_[ ARG0, ARG1 ];
    
    if ($event =~ /^_/) {
        my @arg = map { defined() ? $_ : "(undef)" } @$args;
        print "$event = @arg\n";
    } else {
        print "---- $event = ( @$args )\n";
    }
}


exit;

