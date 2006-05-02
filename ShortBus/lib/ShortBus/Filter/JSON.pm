package ShortBus::Filter::JSON;

use JSON;

sub new {
    bless( [],shift );
}

sub freeze {
    shift;
    my $obj = shift;
    my ($ev, $ch) = @$obj{qw( eid channel )};
    $ch =~ s/'/\\'/g;
    "<script>sb($ev,'$ch','".objToJson($obj)."');</script>\n";
}

sub thaw {
    shift;
    jsonToObj(shift);
}

1;
