package Cometd::Plugin::JSONTransport;

sub BAYEUX_VERSION() { '1.0' }

sub new {
    bless({}, shift);
}

sub handle {
    my ( $self, $event ) = @_;
    if ( defined( __PACKAGE__.'::'.$event ) ) {
        $self->$event( splice( @_, 2, $#_ ) );
    }
    return 1;
}

sub connected {
    my ( $self, $cheap, $socket, $wheel ) = @_;
    $cheap->{transport} = 'JSON';
    if ( $wheel ) {
        $wheel->put( "bayeux ".BAYEUX_VERSION );
    }
}

sub remote_input {
    warn "JSONTransport received: $_[ 2 ]";
    # XXX send data to channel manager
}
1;
