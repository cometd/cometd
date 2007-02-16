package Cometd::Plugin::Memcached;

use Cometd::Plugin;
use base 'Cometd::Plugin';

use POE qw( Filter::Memcached );
use Scalar::Util qw( weaken );
use Data::Dumper;

use strict;
use warnings;

sub new {
    my $class = shift;
    $class->SUPER::new(
        name => 'Memcached',
        queue => [],
        listener => undef,
        event_connected => undef,
        event_receive => undef,
        @_
    );
}

sub as_string {
    __PACKAGE__;
}

# ---------------------------------------------------------
# Client

sub remote_connected {
    my $self = shift;
    my ( $client, $con, $socket ) = @_;
    
    $self->take_connection( $con );
    
    # POE::Filter::Stackable object:
    $con->filter->push( POE::Filter::Memcached->new() );
    
    $con->filter->shift(); # POE::Filter::Stream
   
    # FIXME
    $self->{con} = $con;
    weaken( $self->{con} );
    
    if ( $self->{event_connected} && ref( $self->{event_connected} ) ) {
        $self->{event_connected}->( @_ );
    } elsif ( $self->{event_connected} && $self->{listener} ) {
        $poe_kernel->post( $self->{listener} => $self->{event_connected} => @_ );
    }
}

sub remote_receive {
    my $self = shift;
    my ( $client, $con, $data ) = @_;
    
#    $self->_log(v => 4, msg => 'data:'.Data::Dumper->Dump([$data]));
   
    # pull off the top of the queue
    my ( $cmd, $item ) = $self->_queue;

    $item->{cmd} = $cmd;
    
#    $data->{cmd} ||= 'unknown';
#    $self->_log( v => 4, msg => "cmd in: $data->{cmd} queue: $cmd" );
    
    my $callback = delete $item->{callback};
    if ( ref( $callback ) ) {
        $callback->( $item, $data )
            if ( $callback );
    } elsif ( $self->{listener} && $self->{event_receive} ) {
        $poe_kernel->post( $self->{listener} => $self->{event_receive} => $item => $data );
    } elsif ( $self->{listener} && $callback ) {
        $poe_kernel->post( $self->{listener} => $callback => $item => $data );
    } else {
        # XXX no response for command
    }
    
    return 1;
}

# ---------------------------------------------------------

# put or pull from the queue
# DO NOT call this yourself
sub _queue {
    my ( $self, $cmd, $data ) = @_;
    if ( $cmd ) {
        push(@{$self->{queue}},[ $cmd, $data ]);
    } else {
        my $out = shift @{$self->{queue}};
        return $out ? @{ $out } : undef;
    }
}


# external object methods

sub get {
    my ( $self, $data ) = @_;
    $self->_queue( 'get', $data );
    
    $self->{con}->send({
        cmd => 'get',
        ( map {
            exists( $data->{$_} ) ? ( $_ => $data->{$_} ) : ()
        } qw( key keys ) )
    });
}

sub set {
    my ( $self, $data, $cmd ) = @_;
    $cmd ||= 'set';
    
    $self->_queue( $cmd, $data );

    $self->{con}->send({
        cmd => $cmd,
        key => $data->{key},
        obj => $data->{obj},
        ( defined( $data->{flags} ) ? ( flags => $data->{flags} ) : () ),
        ( defined( $data->{exp} ) ? ( exp => $data->{exp} ) : () )
    });
}

sub add {
    my $self = shift;
    $self->set( $_[0], 'add' );
}

sub replace {
    my $self = shift;
    $self->set( $_[0], 'replace' );
}

sub delete {
    my ( $self, $data ) = @_;
    $self->_queue( 'delete', $data );

    $self->{con}->send({
        cmd => 'delete',
        key => $data->{key},
        ( defined( $data->{time} ) ? ( time => $data->{time} ) : () ),
    });
}

sub incr {
    my ( $self, $data ) = @_;
    $self->_queue( 'incr', $data );

    $self->{con}->send({
        cmd => 'incr',
        key => $data->{key},
        ( defined( $data->{by} ) ? ( by => $data->{by} ) : () ),
    });
}

sub decr {
    my ( $self, $data ) = @_;
    $self->_queue( 'decr', $data );

    $self->{con}->send({
        cmd => 'decr',
        key => $data->{key},
        ( defined( $data->{by} ) ? ( by => $data->{by} ) : () ),
    });
}

sub version {
    my ( $self, $data ) = @_;
    $self->_queue( 'version', $data );

    $self->{con}->send({
        cmd => 'version',
    });
}

sub stats {
    my ( $self, $data ) = @_;
    $self->_queue( 'stats', $data );

    $self->{con}->send({
        cmd => 'stats',
    });
}

sub flush_all {
    my ( $self, $data ) = @_;
    $self->_queue( 'flush_all', $data );

    $self->{con}->send({
        cmd => 'flush_all',
    });
}

# XXX don't implement quit unless needed

1;
