package ShortBus;

use Carp qw(croak);

use strict;
use warnings;

sub import {
    my $self = shift;

    my @modules = @_;

    push(@modules,'Common');

    my $package = caller();
    my @failed;

    foreach my $module (@modules) {
        my $code = "package $package; use ShortBus::$module;";
        eval($code);
        if ($@) {
            warn $@;
            push(@failed, $module);
        }
    }

    @failed and croak "could not import qw(" . join(' ', @failed) . ")";
}

sub new {
    my $class = shift;
    warn "new cannot be used in $class";
}

1;
