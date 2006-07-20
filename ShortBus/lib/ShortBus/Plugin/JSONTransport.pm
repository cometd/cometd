package ShortBus::Plugin::JSONTransport;


sub new {
    bless({}, shift);
}

sub handle {
    my ($self, $socket, $wheel, $cheap) = @_;
    $cheap->{transport} = 'JSON';
    return 1;
}


1;
