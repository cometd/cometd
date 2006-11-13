package Cometd::Plugin::Manager;

use Cometd::Plugin;
use base 'Cometd::Plugin';

use POE::Filter::Line;
use Data::Dumper;

use strict;
use warnings;

sub new {
    my $class = shift;
    bless({
        @_
    }, $class);
}

sub as_string {
    __PACKAGE__;
}

# ---------------------------------------------------------
# server

sub local_connected {
    my ( $self, $server, $con, $socket ) = @_;
    $con->transport( 'Manager' );
    if ( $con->wheel ) {
        # POE::Filter::Stackable object:
        my $filter = $con->wheel->[ POE::Wheel::ReadWrite::FILTER_INPUT ];
        
        $filter->push(
            POE::Filter::Line->new(),
        );
        
        $con->send( "Cometd Manager - commands: dump, quit" );
        # XXX should we pop the stream filter off the top?
    }
    return;
}

sub local_receive {
    my ($self, $server, $con, $data) = @_;
    warn "manager:".Data::Dumper->Dump([ $data ]);
    if ( $data =~ m/^help/i ) {
        $con->send( "commands: dump, quit" );
    } elsif ( $data =~ m/^dump/i ) {
        $con->send( Data::Dumper->Dump([ $server ]) );
    } elsif ( $data =~ m/^quit/i ) {
        $con->send( "goodbye." );
        $con->close_on_flush( 1 );
    }
    return;
}

1;
