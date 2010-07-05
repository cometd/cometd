#!/usr/bin/perl

die "don't use this for now, ask xantus";

use lib qw( ./lib );

use POE qw(
    Component::Client::TCP
    Filter::Line
);

use JSON;
use Carp qw( cluck );

$SIG{__DIE__} = \&cluck;

POE::Component::Client::TCP->new(
        RemoteAddress  => "127.0.0.1",
        RemotePort     => "6000",
        BindAddress    => "127.0.0.1",
        Alias          => 'client',
        ConnectTimeout => 10,              # Seconds; optional.

        SessionParams => [ options => { debug => 1 } ], # Optional.

        Started        => \&handle_start,   # Optional.
        Args           => [ "arg0", "arg1" ],  # Optional.  Start args.

        Connected      => \&handle_connect,
        ConnectError   => \&handle_connect_error,
        Disconnected   => \&handle_disconnect,

        ServerInput    => \&handle_server_input,
        ServerError    => \&handle_server_error,
        ServerFlushed  => \&handle_server_flush,

        Filter         => POE::Filter::Line->new(),
    );

$poe_kernel->run();

sub handle_start {
    my @args = @_[ARG0..$#_];
    warn "started";
}

sub handle_connect {
    my ($socket, $peer_address, $peer_port) = @_[ARG0, ARG1, ARG2];
    warn "connect";
    $_[HEAP]->{server}->put(objToJson({
        channel => '/pub/foo',
        data => {
            message => join(' ',@ARGV),
        }
    }));
}

sub handle_connect_error {
    my ($syscall_name, $error_number, $error_string) = @_[ARG0, ARG1, ARG2];
    warn "connect error $error_number $error_string";
}

sub handle_disconnect {
    warn "disconnected";
}

sub handle_server_input {
    my $input_record = $_[ARG0];
    warn "input: $input_record";
}

sub handle_server_error {
    my ($syscall_name, $error_number, $error_string) = @_[ARG0, ARG1, ARG2];
    warn "error $error_number $error_string";
}

sub handle_server_flush {
    $_[KERNEL]->yield('shutdown');
}


exit 0;
