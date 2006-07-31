package Cometd;

use Carp qw(croak);

use strict;
use warnings;

our $VERSION = '0.01';

sub import {
    my $self = shift;

    my @modules = @_;

    push(@modules, 'Common');

    my $package = caller();
    my @failed;

    foreach my $module (@modules) {
        my $code = "package $package; use Cometd::$module;";
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
    warn "new cannot be used in package $class";
}

1;

__END__


=pod

=head1 NAME

Cometd - scalable HTTP-based event routing bus

=head1 SYNOPSIS

    # for a perlbal plugin
    use Cometd qw( Perlbal::Service );

=head1 ABSTRACT

Cometd is a scalable HTTP-based publish / subscribe event routing bus

=head1 DESCRIPTION

Cometd uses a pattern commonly known as Comet (HTTP Push)

=head1 SEE ALSO

L<Perlbal>

=head1 AUTHOR

David Davis E<lt>xantus@cometd.comE<gt>

=head1 RATING

Please rate this module. L<http://cpanratings.perl.org/rate/?distribution=Cometd>

=head1 COPYRIGHT AND LICENSE

Copyright 2006 by David Davis

This library is free software; you can redistribute it and/or modify
it under the terms of the [TODO LICENSE HERE] license.

=cut

