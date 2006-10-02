package Cometd::Plugin::JSONTransport;

use POE::Filter::Line;
use POE::Filter::JSON;

use strict;
use warnings;

sub BAYEUX_VERSION() { '1.0' }

sub new {
    my $class = shift;
    bless({
        @_
    }, $class);
}

sub handle {
    my ( $self, $event ) = @_;
    if ( defined( __PACKAGE__ . '::' . $event ) ) {
        $self->$event( splice( @_, 2, $#_ ) );
    }
    return 1;
}

sub remote_connected {
    my ( $self, $cheap, $socket, $wheel ) = @_;
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
    my ( $self, $cheap, $socket, $wheel ) = @_;
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
    my ($self, $cheap, $event) = @_;
    $self->{chman}->deliver($cheap, $event);
}

sub local_receive {
    my ($self, $cheap, $event) = @_;
    $self->{chman}->deliver($cheap, $event);
}

1;
