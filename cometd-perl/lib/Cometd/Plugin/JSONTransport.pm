package Cometd::Plugin::JSONTransport;

use Cometd::Plugin;
use base 'Cometd::Plugin';

use POE::Filter::Line;
use POE::Filter::JSON;

use strict;
use warnings;

use constant BAYEUX_VERSION => '1.0';
use constant NAME => 'JSON';

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
# Client

sub remote_connected {
    my ( $self, $client, $cheap, $socket, $wheel ) = @_;
    $cheap->transport( NAME );
    if ( $wheel ) {
        # POE::Filter::Stackable object:
        my $filter = $wheel->[ POE::Wheel::ReadWrite::FILTER_INPUT ];
        
        $filter->push(
            POE::Filter::Line->new()
        );
        
        $wheel->put( "bayeux " . BAYEUX_VERSION );
            
        $filter->push(
            POE::Filter::JSON->new()
        );
        
        # XXX should we pop the stream filter off the top?
    }
    return 1;
}

sub remote_receive {
    my ($self, $client, $cheap, $event) = @_;
    $self->{chman}->deliver($cheap, $event);
    return 1;
}


# ---------------------------------------------------------
# server

sub local_connected {
    my ( $self, $server, $cheap, $socket, $wheel ) = @_;
    $cheap->transport( NAME );
    if ( $wheel ) {
        # POE::Filter::Stackable object:
        my $filter = $wheel->[ POE::Wheel::ReadWrite::FILTER_INPUT ];
        
        $filter->push(
            POE::Filter::Line->new(),
            POE::Filter::JSON->new(),
        );
        
        $wheel->put({ bayeux => BAYEUX_VERSION });
        
        # XXX should we pop the stream filter off the top?
    }
    return 1;
}

sub local_receive {
    my ($self, $server, $cheap, $event) = @_;
    $self->{chman}->deliver($cheap, $event);
    return 1;
}

1;
