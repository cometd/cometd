#!/usr/bin/perl

use POE::Loop::Glib;
use Glib;
use IO::AIO;
use Fcntl;

use POE qw( Wheel::ReadWrite Filter::Line Driver::SysRW );

Glib::IO->add_watch( IO::AIO::poll_fileno, in => sub { IO::AIO::poll_cb; 1 } );

POE::Session->create(
    inline_states => {
        _start => sub {
            $_[KERNEL]->alias_set('foo');
            my $session = $_[SESSION];
            aio_open( "/etc/passwd", O_RDONLY, 0, $session->postback( 'opened', '/etc/passwd' ) );
            return;
        },
        opened => sub {
            my $file = $_[ ARG0 ]->[0];
            my $fh = $_[ ARG1 ]->[0];
            warn "opened $file";
            $_[HEAP]->{wheel} = POE::Wheel::ReadWrite-new(
                InputHandle => $fh,
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
            warn "error";
            delete $_[HEAP]->{wheel};
            return;
        },
    },
);

$poe_kernel->run();

1;
