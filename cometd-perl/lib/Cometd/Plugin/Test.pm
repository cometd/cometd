package Cometd::Plugin::Test;

# used for tests in t/

use Cometd qw( Plugin );
use base 'Cometd::Plugin';

use POE::Filter::Line;

use strict;
use warnings;

sub new {
    my $class = shift;
    my $self = $class->SUPER::new(
        name => 'Test',
        @_
    );

    my $tpl = $self->{template};
    $self->{template} = [ (<$tpl>) ]
        if ( $tpl && ref $tpl eq 'GLOB' );
    
    die "must specify template for tests"
        unless( $self->{template} );

    return $self;
}

sub as_string {
    __PACKAGE__;
}

sub next_item {
    my $self = shift;
    
    shift @{$self->{template}};
}

# ---------------------------------------------------------
# server

sub local_connected {
    my ( $self, $server, $con, $socket ) = @_;
    
    $self->take_connection( $con );
    # POE::Filter::Stackable object:
    $con->filter->push( POE::Filter::Line->new() );

    Test::More::pass("connected, starting test");
    
    my $n = $self->next_item();
    if ( $n ) {
        Test::More::pass("sent '$n'");
        $con->send( $n );
    } else {
        Test::More::fail("no test data in the template");
        kill(INT => $$);
        return;
    }

    return 1;
}

sub local_receive {
    my ( $self, $server, $con, $data ) = @_;
    
    my $n = $self->next_item();

    unless ( $n ) {
        Test::More::fail("data received '$data' but no matching item");
        kill(INT => $$);
        return;
    }

    if ( $data =~ m/^$n$/ ) {
        my $send = $self->next_item();
        Test::More::pass("received valid result for '$n'");
        if ( $send ) {
            Test::More::pass("sending '$send'");
            $con->send( $send );
        } else {
            Test::More::pass("last item in template, end of test");
            $con->close();
        }
    } else {
        Test::More::fail("received INVALID result for '$n' : '$data'");
        kill(INT => $$);
        return;
    }
    
    return 1;
}

sub local_disconnected {
    my ( $self, $server, $con ) = @_;
    $server->shutdown();
    Test::More::pass("local disconnected");
}

# ---------------------------------------------------------
# client

sub remote_connected {
    my ( $self, $client, $con, $socket ) = @_;

    $self->take_connection( $con );

    # POE::Filter::Stackable object:
    $con->filter->push( POE::Filter::Line->new() );

    return 1;
}

sub remote_receive {
    my ( $self, $client, $con, $data ) = @_;
    
    my $n = $self->next_item();

    unless ( $n ) {
        Test::More::fail("data received '$data' but no matching item");
        kill(INT => $$);
        return;
    }

    if ( $data =~ m/^$n$/ ) {
        Test::More::pass("received valid result for '$n'");
        my $send = $self->next_item();
        if ( $send ) {
            Test::More::pass("sending '$send'");
            $con->send( $send );
        } else {
            Test::More::pass("east item in template, end of test");
            $con->close();
        }
    } else {
        Test::More::fail("received INVALID result for '$n' : '$data'");
        kill(INT => $$);
        return;
    }
    
    return 1;
}

sub remote_disconnected {
    my ( $self, $client, $con ) = @_;
    Test::More::pass("remote disconnected");
    $client->shutdown();
}

sub remote_connect_timeout {
    warn "remote connect timeout";
}

sub remote_connect_error {
    warn "remote connect error";
}

sub remote_error {
    warn "remote error";
}

1;
