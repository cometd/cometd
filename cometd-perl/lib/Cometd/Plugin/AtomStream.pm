package Cometd::Plugin::AtomStream;

use Cometd::Plugin;
use base 'Cometd::Plugin';

use POE::Filter::Atom;
use POE::Filter::Line;

use strict;
use warnings;

sub new {
    my $class = shift;
    $class->SUPER::new(
        name => 'AtomStream',
        @_
    );
}

sub as_string {
    __PACKAGE__;
}

# ---------------------------------------------------------
# Client

# TODO reconnect with resume code

sub remote_connected {
    my ( $self, $client, $con, $socket ) = @_;
    $self->take_connection( $con );
    # POE::Filter::Stackable object:
    my $filter = $con->filter;

    $filter->push( POE::Filter::Line->new() );

    # look for the atom stream header first
    $self->{_header} = 1;

    # TODO http::request?
    $con->send(
        "GET /atom-stream.xml HTTP/1.0",
        "Host: updates.sixapart.com",
        "Connection: close",
        ""
    );
    
    return 1;
}

sub remote_receive {
    my ($self, $client, $con, $d) = @_;
    
    if ( $self->{_header} ) {
        #warn "received [$d]";
        if ( $d =~ m/^<atomStream/io ) {
            delete $self->{_header};
            $con->filter->push( POE::Filter::Atom->new() );
        }
    } else {
        return unless ( $d->can( "entries" ) );

        my @entries = $d->entries;
        foreach ( @entries ) {
            my $link = $_->link->href;
            $self->_log( v => 4, msg => "Title:[ ".$_->title." ] Link:[ $link ]");
        }
    }
    
    return 1;
}

1;
