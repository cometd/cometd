#!/usr/bin/perl
# $Id$
use warnings;
use strict;

our $VERSION;
$VERSION = do {my($r)=(q$Revision$=~/(\d+)/);sprintf"1.%04d",$r};

use lib "./lib";

use POE qw( Component::ShortBus );

POE::Session->create(
    inline_states => {
        _start => \&start,
        _default => \&trace_unknown,
    },
);

$poe_kernel->run();

sub start {
    my ( $kernel, $heap ) = @_[ KERNEL, HEAP ];

    my $ui = $heap->{ui} = ShortBus::Interface->new();
    my $bus = $heap->{b} = POE::Component::ShortBus->new({
        BindAddress => '0.0.0.0',
        BindPort => '2020',
    });

    $bus->add_router( ShortBus::Socket->new() );

    $ui->add_bus( $bus );
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

{

package ShortBus::Socket;

use warnings;
use strict;

sub new {
    my $class = shift;
    return bless [], $class;
}

our $AUTOLOAD;
sub AUTOLOAD {
    my $self = shift;
    warn "$self->$AUTOLOAD(@_)";
}

1;

}


{

package ShortBus::Interface;

use warnings;
use strict;

sub new {
    my $class = shift;
    return bless [], $class;
}

our $AUTOLOAD;
sub AUTOLOAD {
    my $self = shift;
    warn "$self->$AUTOLOAD(@_)";
}

1;

}
