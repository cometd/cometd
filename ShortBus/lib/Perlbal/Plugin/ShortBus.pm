package Perlbal::Plugin::ShortBus;

use strict;
use warnings;

use ShortBus qw( Perlbal::Service );

1;

__END__

=pod

=head1 NAME

Perlbal::Plugin::ShortBus - Perlbal plugin for ShortBus

=head1 SYNOPSIS
    # perlbal.conf

    LOAD ShortBus
    LOAD vhosts

    SERVER max_connections = 10000

    CREATE POOL apache
        POOL apache ADD 127.0.0.1:81
    CREATE SERVICE apache_proxy
        SET role = reverse_proxy
        SET pool = apache
        SET persist_backend = on
        SET backend_persist_cache = 2
        SET verify_backend = on
        SET enable_reproxy = true
    ENABLE apache_proxy

    CREATE SERVICE shortbus
        SET role = reverse_proxy
        SET plugins = ShortBus
    ENABLE shortbus

    CREATE SERVICE web
        SET listen         = 10.0.0.1:80
        SET role           = selector
        SET plugins        = vhosts
        SET persist_client = on
        VHOST *.yoursite.com = apache_proxy
        VHOST *              = apache_proxy
    ENABLE web

=head1 ABSTRACT

This plugin allows Perlbal to put clients into a push type connection state.

=head1 DESCRIPTION

This Plugin works by keeping a conneciton open after an external webserver has
authorized the client.  That way, your valuable http processes can continue
servicing other clients.

The easiest way to use this module is to setup apache on port 81, and Perlbal
on port 80 as in the synopsis.  Start perbal and use a supported javascript
library.  You can find supported libraries at L<http://shortbus.xantus.org/>

=head2 ShortBus Notes

=head2 EXPORT

Nothing.

=head1 SEE ALSO

L<Perlbal>

=head1 AUTHOR

David Davis E<lt>xantus@cpan.orgE<gt>

=head1 RATING

Please rate this module. L<http://cpanratings.perl.org/rate/?distribution=Perlbal-Plugin-ShortBus>

=head1 COPYRIGHT AND LICENSE

Copyright 2006 by David Davis

This library is free software; you can redistribute it and/or modify
it under the terms of the [TODO LICENSE HERE] license.

=cut

