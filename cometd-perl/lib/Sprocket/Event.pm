package Sprocket::Event;

use JSON;

use strict;
use warnings;

sub new {
    my $class = shift;
    bless({
        data => {
            @_
        }
    }, ref $class || $class);
}

sub as_string {
    my $self = shift;
    my $json = eval { objToJson( $self->{data} ) };
    return $json;
}

sub channel {
    shift->{data}->{channel};
}

1;

