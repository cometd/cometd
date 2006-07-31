package Cometd::Filter::JSON;

use JSON;

sub new {
    bless( [],shift );
}

sub freeze {
    my $obj = $_[1];
    # save space
    my ($ev, $ch) = delete @$obj{qw( eid channel )};
    # TODO proper js escape, also, does JSON escape?
    $ch =~ s/'/\\'/g;
    "<script>cometd($ev,'$ch','".objToJson($obj)."');</script>\n";
}

sub thaw {
    jsonToObj($_[1]);
}

1;
