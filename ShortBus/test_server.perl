#!/usr/bin/perl
# $Id $
use warnings;
use strict;

# simple test server
# accepts filter::ref connections from a cgi

#our $VERSION;
#$VERSION = do {my($r)=(q$Revision$=~/(\d+)/);sprintf"1.%04d",$r};

use lib "./lab";

use POE;
use POE::Component::ShortBus qw( SB_RC_OK );
use POE::Filter::Reference;
use POE::Component::Server::TCP;

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

    POE::Component::Server::TCP->new(
        Alias => "shortbus_server",
        Address      => "0.0.0.0",
        Port         => 2021,
        ClientFilter => "POE::Filter::Reference",
        ClientInput => sub {
            my ( $heap, $session, $d ) = @_[ HEAP, SESSION, ARG0 ];
            my ($status, $id);

            if (ref($d) eq 'ARRAY') {
                foreach (@$d) {
                    next unless (ref($_) eq 'HASH');

                    if (!exists($_->{id})) {

                        my ( $rt, $queue_id ) = $bus->create( "created" );
    
                        if ( $rt == SB_RC_OK ) {
                            $bus->connect( "connected", $queue_id, $session->postback("dataRecv", $queue_id) );
                            $heap->{__id} = $id = $queue_id;
                        } else {
                            $status = 500;
                        }

                    } elsif (exists($_->{id}) && !exists($heap->{__id})) {

                        my ($rt) = $bus->connect( "connected", $_->{id}, $session->postback("dataRecv", $_->{id}) );

                        if ( $rt == SB_RC_OK ) {
                            $heap->{__id} = $id = $_->{id};
                        } else {
                            $status = 404;
                        }
                    } elsif (exists($heap->{__id}) && $_->{post}) {
                        # TODO return value
                        $bus->post("post", $_->{to_id}, $_->{post});
                    }

                }

                $status = 200;
            } else {
                $status = 500;
            }
            $heap->{client}->put( { status => $status, id => $id } );
        },
        ClientDisconnected => sub {
            my $kernel = $_[KERNEL];
            if (exists($heap->{__id})) {
                $bus->disconnect("disconnected", $heap->{__id});
            }
            #$kernel->post( shortbus_server => "shutdown" );
        },
        InlineStates => {
            dataRecv => sub {
                my ($heap, $data) = @_[HEAP, ARG0];
                $heap->{client}->put( { event => 'post', data => $data } );
                warn "data recv from client";
            },
        },
    );

    $kernel->refcount_increment($bus->ID, "shortbus");
    
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

