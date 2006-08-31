package Cometd::Filter::JSON;

use JSON;

sub new {
    bless( [],shift );
}

sub freeze {
    my $obj = $_[1];
    my $ch = $obj->{channel};
    # TODO proper js escape, also, does JSON escape?
    $ch =~ s/'/\\'/g;
    "<script>cometd('$ch',".objToJson($obj).");</script>\n";
}

sub thaw {
    jsonToObj($_[1]);
}

1;
