package POE::Component::ShortBus::Plugin::TCP;

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

