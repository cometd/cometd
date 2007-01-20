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
        plugin_name => 'Memcached',
        @_
    );
}

sub as_string {
    __PACKAGE__;
}

# ---------------------------------------------------------
# Client

sub remote_connected {
    my ( $self, $client, $con, $socket ) = @_;
    $con->transport( $self->plugin_name );
    
    # POE::Filter::Stackable object:
    my $filter = $con->filter;
    $filter->push( POE::Filter::Memcached->new() );
    
    # simple tests

    $con->send( { cmd => 'stats' } );
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
    
    return 1;
}

sub remote_receive {
    my ($self, $client, $con, $data) = @_;

    $self->_log(v => 4, msg => 'data:'.Data::Dumper->Dump([$data]));
    
    return 1;
}

1;
