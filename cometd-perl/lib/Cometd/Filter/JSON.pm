package Cometd::Filter::JSON;

use JSON;

sub new {
    bless( [],shift );
}

# FIXME JSON.pm throws errors with invalid json
# we must wrap it in eval

sub freeze {
    my $obj = $_[1];
    my $ch = $obj->{channel};
    # TODO proper js escape, also, does JSON escape?
    if ($ch) {
        $ch =~ s/'/\\'/g;
    } else {
        $ch = "/meta/unknown";
    }
    "<script>deliver('$ch',".objToJson($obj).");</script>\n";
}

sub thaw {
    jsonToObj($_[1]);
}

1;
