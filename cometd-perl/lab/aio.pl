#!/usr/bin/perl

use strict;
use warnings;

use IO::AIO;
use Fcntl;

use POE qw( Wheel::ReadWrite Filter::Line Driver::SysRW );

POE::Session->create(
    inline_states => {
        _start => sub {
            $_[KERNEL]->alias_set('foo');
            my $session = $_[SESSION];

            open my $fh, "<&=".IO::AIO::poll_fileno or die "$!";
            
            $_[KERNEL]->select_read($fh, "aio_event");
            
            aio_open( "/etc/passwd", O_RDONLY, 0, $session->postback( 'opened', '/etc/passwd' ) );
            
            return;
        },
        aio_event => sub {
            warn "aio event occurred";
            # don't select_read here in a real world app (you want it to poll aio)
            # this is here so the test will exit clean when everything is done
            $_[KERNEL]->select_read($_[ARG0]);
            IO::AIO::poll_cb();
        },
        opened => sub {
            my ( $file, $fh ) = ( $_[ ARG0 ]->[0], $_[ ARG1 ]->[0] );
            warn "opened file $file";
            $_[HEAP]->{wheel} = POE::Wheel::ReadWrite->new(
                Handle => $fh,
                Driver => POE::Driver::SysRW->new(),
                Filter => POE::Filter::Line->new(),
                InputEvent => 'input',
                ErrorEvent => 'error',
            );
            return;
        },
        input => sub {
            warn "line: $_[ARG0]\n";
        },
        error => sub {
            warn "error @_[ARG0,ARG1] ( 0 is eof )";
            delete $_[HEAP]->{wheel};
            return;
        },
    },
);

$poe_kernel->run();

1;
