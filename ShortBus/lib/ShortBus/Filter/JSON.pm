package ShortBus::Filter::JSON;

use JSON;

sub new {
    bless( [],shift );
}

sub freeze {
    shift;
    return "<script>sb('".objToJson(shift)."');</script>\n";
}

sub thaw {
    shift;
    return jsonToObj(shift);
}

1;
