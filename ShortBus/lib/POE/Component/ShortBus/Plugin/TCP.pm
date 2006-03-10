package POE::Component::ShortBus::Plugin::Client::TCP;

use warnings;
use strict;

sub new {
    my $class = shift;
    return bless {}, $class;
}

our $AUTOLOAD;
sub AUTOLOAD {
    my $self = shift;
    warn "$self->$AUTOLOAD(@_)";
}

1;

