package Cometd::Plugin::JSONTransport;

use base 'Cometd::Plugin';

use POE::Filter::Line;
use POE::Filter::JSON;

use strict;
use warnings;

sub BAYEUX_VERSION() { '1.0' }

sub as_string {
    __PACKAGE__;
}

sub new {
    my $class = shift;
    bless({
        @_
    }, $class);
}

sub remote_connected {
    my ( $self, $client, $cheap, $socket, $wheel ) = @_;
    $cheap->{transport} = 'JSON';
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
    return;
}

sub local_connected {
    my ( $self, $server, $cheap, $socket, $wheel ) = @_;
    $cheap->{transport} = 'JSON';
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
    return;
}

sub remote_receive {
    my ($self, $client, $cheap, $event) = @_;
    $self->{chman}->deliver($cheap, $event);
}

sub local_receive {
    my ($self, $server, $cheap, $event) = @_;
    $self->{chman}->deliver($cheap, $event);
}

1;
