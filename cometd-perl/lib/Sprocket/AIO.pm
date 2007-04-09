package Sprocket::AIO;

use Fcntl;
use POE;

use strict;
use warnings;

use overload '""' => sub { shift->as_string(); };

BEGIN {
    eval "use IO::AIO qw( poll_fileno poll_cb 2 )";
    if ( $@ ) {
        eval 'sub HAS_AIO () { 0 }';
    } else {
        eval 'sub HAS_AIO () { 1 }';
        eval 'IO::AIO::min_parallel 16';
    }
}

our $singleton;

sub new {
    my $class = shift;
    return $singleton if ( $singleton );
    return unless ( HAS_AIO );

    my $self = $singleton = bless({
        session_id => undef,
        @_
    }, ref $class || $class );

    POE::Session->create(
        object_states =>  [
            $self => [qw(
                _start
                _stop
                poll_cb
            )]
        ],
    );

    return $self;
}

sub as_string {
    __PACKAGE__;
}

sub _start {
    my ( $self, $kernel ) = @_[ OBJECT, KERNEL ];
    
    $self->{session_id} = $_[ SESSION ]->ID();
    
    $kernel->alias_set( "$self" );
    
    # eval here because poll_fileno isn't imported when IO::AIO isn't installed
    open( my $fh, "<&=".eval "poll_fileno()" ) or die "$!";
    $kernel->select_read( $fh, 'poll_cb' );
   
    $self->_log( v => 1, msg => 'AIO support module started' );
   
    return;
}

sub _stop {
    $_[ OBJECT ]->_log(v => 1, msg => 'stopped');
}

sub _log {
    $poe_kernel->call( shift->{parent_id} => _log => ( call => ( caller(1) )[ 3 ], @_ ) );
}

1;
