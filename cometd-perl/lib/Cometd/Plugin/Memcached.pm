package Cometd::Plugin::Memcached;

use Cometd::Plugin;
use base 'Cometd::Plugin';

use POE::Filter::Memcached;

use Data::Dumper;

use strict;
use warnings;

sub new {
    my $class = shift;
    $class->SUPER::new(
        name => 'Memcached',
        queue => [],
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
   
    # FIXME
    $self->{con} = $con;
    
    $self->{event_connected}->( @_ )
        if ( $self->{event_connected} );
    
    return 1;
    
    $con->send( { cmd => 'version' } );

    $con->send( { cmd => 'set', key => 'foo2', obj => 'bar test' } );
    $con->send( { cmd => 'get', key => 'foo2' } );
    $con->send( { cmd => 'delete', key => 'foo2' } );
    $con->send( { cmd => 'get', key => 'foo2' } );
    $con->send( { cmd => 'set', key => 'foo2', obj => 'foobar' } );
    
    $con->send( { cmd => 'set', key => 'foo3', obj => 12 } );
    
    $con->send( { cmd => 'incr', key => 'foo3' } );
    $con->send( { cmd => 'decr', key => 'foo3', by => 2 } );
    
    $con->send( { cmd => 'get', keys => [ 'foo2','foo3','foo2','foo' ] } );
    
    $con->send( { cmd => 'flush_all' } );
}

sub remote_receive {
    my $self = shift;
    my ( $client, $con, $data ) = @_;
    
    $self->_log(v => 4, msg => 'data:'.Data::Dumper->Dump([$data]));
   
    my ( $cmd, $item ) = $self->queue;
    
    # TODO verify cmd
    $self->_log( v => 4, msg => "cmd in: $data->{cmd} queue: $cmd" );
    
    my $callback = delete $item->{callback};
    $callback->( $item, $data )
        if ( $callback );
    
    return 1;
}

sub queue {
    my ( $self, $cmd, $data ) = @_;
    if ( $cmd ) {
        push(@{$self->{queue}},[ $cmd, $data ]);
    } else {
        my $out = shift @{$self->{queue}};
        return $out ? @{ $out } : undef;
    }
}

sub stats {
    my ( $self, $data ) = @_;
    $self->queue( 'stats', $data );
    
    $self->{con}->send( { cmd => 'stats' } );
}

sub get {
    my ( $self, $data ) = @_;
    $self->queue( 'get', $data );
    
    $self->{con}->send({
        cmd => 'get',
        ( map {
            exists( $data->{$_} ) ? ( $_ => $data->{$_} ) : ()
        } qw( key keys ) )
    });
}

sub set {
    my ( $self, $data ) = @_;
    $self->queue( 'set', $data );

    $self->{con}->send({
        cmd => 'set',
        key => $data->{key},
        obj => $data->{obj},
    });
}

1;
