package Sprocket::Plugin::AtomStream;

use Sprocket qw( Plugin Event );
use base 'Sprocket::Plugin';

use POE;
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
    $con->filter->push( POE::Filter::Line->new() );

    $con->filter->shift(); # pull off Filter::Stream

    # look for the atom stream header first
    $con->{_header} = 1;

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
    
    if ( $con->{_header} ) {
        return unless ( $d && $d =~ m/^<atomStream/io );

        delete $con->{_header};
        $con->filter->push( POE::Filter::Atom->new() );
    
        $con->filter->shift(); # POE::Filter::Stream
        
    } else {
        return unless ( $d->can( "entries" ) && $self->{event_manager} && $self->{feed_channel} );

        my @events = map {
#            $self->_log(v => 4, msg => 'Title:[ '.$_->title.' ] Link:[ '.$_->link->href.' ]');
            ( $_ && $_->link )
            ? new Sprocket::Event(
                channel => $self->{feed_channel},
                data => 'Title:[ '.$_->title.' ] Link:[ '.$_->link->href.' ]'
            ) : ();
        } $d->entries;
        
        return unless ( @events );
        
        
        $poe_kernel->call( $self->{event_manager} => deliver_events => \@events );
    }
    
    return 1;
}

1;
