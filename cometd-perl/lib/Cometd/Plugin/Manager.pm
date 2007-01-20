package Cometd::Plugin::Manager;

use Cometd::Plugin;
use base 'Cometd::Plugin';

use POE::Filter::Line;
use Data::Dumper;

use strict;
use warnings;

sub new {
    my $class = shift;
    $class->SUPER::new(
        plugin_name => 'Manager',
        @_
    );
}

sub as_string {
    __PACKAGE__;
}

# ---------------------------------------------------------
# server

sub local_connected {
    my ( $self, $server, $con, $socket ) = @_;
    
    $con->transport( $self->plugin_name );

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
    
    $self->_log( v => 4, msg => "manager:".Data::Dumper->Dump([ $data ]));
    
    if ( $data =~ m/^help/i ) {
        $con->send( "commands: dump, quit" );
    } elsif ( $data =~ m/^dump (.*)/i ) {
        $con->send( eval "Data::Dumper->Dump([$1])" );
    } elsif ( $data =~ m/^x (.*)/i ) {
        $con->send( eval "$1" );
    } elsif ( $data =~ m/^quit/i ) {
        $con->send( "goodbye." );
        $con->close();
    }
    
    return 1;
}

1;
