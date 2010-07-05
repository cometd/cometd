#!/usr/bin/perl
# $Id$
use warnings;
use strict;

#our $VERSION;
#$VERSION = do {my($r)=(q$Revision$=~/(\d+)/);sprintf"1.%04d",$r};

use lib "./lib";

use POE;
use POE::Component::Cometd qw( SB_RC_OK );

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

    my $bus = $heap->{b} = POE::Component::CometCometdd->new();

    $kernel->refcount_increment($bus->ID, "cometd");
    
    my ( $rt, $queue_id ) = $bus->create( "created" );
    
    if ( $rt != SB_RC_OK ) {
        die "queue create failed";
    }
   
    $bus->connect( "connected", $queue_id, $bus );
}

sub queue_created {
    my ( $kernel, $heap, $id ) = @_[ KERNEL, HEAP, ARG1 ];
    
    warn "queue created: $id->[1]";
}

sub trace_unknown {
    my ( $event, $args ) = @_[ ARG0, ARG1 ];
    
    if ($event =~ /^_/) {
        my @arg = map { defined() ? $_ : "(undef)" } @$args;
        print "$event = @arg\n";
    } else {
        my @arg = map { defined() ? @$_ : "(undef)" } @$args;
        print "---- $event = ( @arg )\n";
    }
}


exit;

