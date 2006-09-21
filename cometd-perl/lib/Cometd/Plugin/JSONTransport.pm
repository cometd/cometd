package Cometd::Plugin::JSONTransport;

use POE::Filter::JSON;

use strict;
use warnings;

sub BAYEUX_VERSION() { '1.0' }

sub new {
    my $class = shift;
    bless({
        filter => POE::Filter::JSON->new(),
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

sub connected {
    my ( $self, $cheap, $socket, $wheel ) = @_;
    $cheap->{transport} = 'JSON';
    if ( $wheel ) {
        $wheel->put( "bayeux " . BAYEUX_VERSION );
    }
}

sub remote_input {
    my $self = shift;

    # TODO use filter stackable here, and migrate from filter line in poco cometd
    # 0 is $cheap, and 1 is plain json
    $self->{chman}->deliver(@_);
}

1;
