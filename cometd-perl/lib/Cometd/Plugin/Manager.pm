package Cometd::Plugin::Manager;

use Cometd::Plugin;
use base 'Cometd::Plugin';

use POE::Filter::Line;
use Data::Dumper;

use strict;
use warnings;

use constant NAME => 'Manager';

sub new {
    my $class = shift;
    bless({
        @_
    }, $class);
}

sub as_string {
    __PACKAGE__;
}

sub plugin_name {
    NAME;
}

# ---------------------------------------------------------
# server

sub local_connected {
    my ( $self, $server, $con, $socket ) = @_;
    
    $con->transport( NAME );

    if ( my $wheel = $con->wheel ) {
        # input_filter and output_filter are the same
        # POE::Filter::Stackable object:
        $wheel->get_input_filter->push(
            POE::Filter::Line->new()
        );
    
        $con->send( "Cometd Manager - commands: dump, quit" );
    
        # XXX should we pop the stream filter off the top?
    }

    return 1;
}

sub local_receive {
    my ( $self, $server, $con, $data ) = @_;
    
    warn "manager:".Data::Dumper->Dump([ $data ]);
    
    if ( $data =~ m/^help/i ) {
        $con->send( "commands: dump, quit" );
    } elsif ( $data =~ m/^dump/i ) {
        $con->send( Data::Dumper->Dump([ $server ]) );
    } elsif ( $data =~ m/^quit/i ) {
        $con->send( "goodbye." );
        $con->close_on_flush( 1 );
    }
    
    return 1;
}

1;
