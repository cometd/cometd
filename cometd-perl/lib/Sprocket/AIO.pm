package Sprocket::AIO;

use IO::AIO;
use Fcntl;

use POE;

use strict;
use warnings;

use overload '""' => sub { shift->as_string(); };

BEGIN {
    eval "use IO::AIO";
    if ( $@ ) {
        eval 'sub HAS_AIO () { 0 }';
    } else {
        eval 'sub HAS_AIO () { 1 }';
    }
}

our $singleton;

sub new {
    my $class = shift;
    return $singleton if ( $singleton );

    my $self = $singleton = bless({
        session_id => undef,
        @_
    }, ref $class || $class );

    return $self unless ( HAS_AIO );
    
    $self->{session_id} =
    POE::Session->create(
        object_states =>  [
            $self => [qw(
                _start
                _stop
                aio_event
            )]
        ],
    )->ID();

    return $self;
}

sub as_string {
    __PACKAGE__;
}

sub _start {
    my ( $self, $kernel ) = @_[OBJECT, KERNEL];
    
    $kernel->alias_set( "$self" );
    $self->_log(v => 1, msg => 'started');
    
    open( my $fh, "<&=".IO::AIO::poll_fileno ) or die "$!";
    $kernel->select_read( $fh => 'aio_event' );
    
    return;
}

sub aio_event {
    IO::AIO::poll_cb();
}
    
sub _stop {
    my ( $self, $kernel ) = @_[OBJECT, KERNEL];
    
    $self->_log(v => 1, msg => 'stopped');
}

sub _log {
    $poe_kernel->call( shift->{parent_id} => _log => ( call => ( caller(1) )[ 3 ], @_ ) );
}

1;
