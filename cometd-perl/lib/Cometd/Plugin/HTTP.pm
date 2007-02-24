package Cometd::Plugin::HTTP;

use Cometd::Plugin;
use base 'Cometd::Plugin';

use POE qw( Filter::HTTPD Filter::Stream Wheel::ReadWrite Driver::SysRW );
use HTTP::Response;
use IO::AIO;
use Fcntl;
use Data::Dumper;

use strict;
use warnings;

sub new {
    my $class = shift;
    
    my $self = $class->SUPER::new(
        name => 'HTTP',
        IndexFile => 'index.html',
        @_
    );

    die "a DocumentRoot is required for plugin $self"
        unless( $self->{document_root} );
    
    $self->{document_root} =~ s!/$!!;

    die "DocumentRoot $self->{document_root} doesn't exist, please create it"
        unless( -d $self->{document_root} );

    return $self;
}

sub add_plugin {
    my $self = shift;
    
    return if ( $self->{_session_id} );
    
    # save the session id
    $self->{_session_id} =
    POE::Session->create(
        object_states =>  [
            $self => [qw(
                _start
                _stop
                aio_event
            )]
        ],
    )->ID();

    return undef;
}

sub as_string {
    __PACKAGE__;
}

sub _start {
    my ( $self, $kernel ) = @_[OBJECT, KERNEL];
    
    $kernel->alias_set( "$self" );

    $self->_log(v => 1, msg => 'started');
    
    open my $fh, "<&=".IO::AIO::poll_fileno or die "$!";

    $kernel->select_read($fh, 'aio_event');
}

sub aio_event {
    IO::AIO::poll_cb();
}

sub _stop {
    my $self = $_[OBJECT];
    $self->_log(v => 1, msg => 'stopped');
}

# ---------------------------------------------------------
# server

sub local_connected {
    my ( $self, $server, $con, $socket ) = @_;
    $self->take_connection( $con );
    # POE::Filter::Stackable object:
    $con->filter->push( POE::Filter::HTTPD->new() );
    $con->filter->shift(); # pull off Filter::Stream
    return 1;
}

sub local_receive {
    my ( $self, $server, $con, $req ) = @_;

    my ( $out, $r );
    
#    open(FH,">>debug.txt");
#    print FH "connection: ".$con->ID."\n";
    $con->{_close} = 1;
    
    if ( ref $req && $req->isa( 'HTTP::Response' ) ) {
        $r = $req; # a prebuilt response
        $con->{_close} = 1; # default anyway
    } elsif ( ref $req && $req->isa( 'HTTP::Request' ) ) {
        my $connection = $req->header( 'connection' );
        $con->{_close} = 0 if ( $connection && $connection =~ m/^keep-alive$/i );
        
        $self->_log( v=> 4, msg => "uri: ".$req->uri );
        
        my $file = $self->{document_root}.$self->resolve_path( $req->uri );
        
        $file .= $self->{index_file}
            if ( $file =~ m!/$! );

        $con->{_content_type} = $self->lookup_content_type( $file );
        
        $con->{_req} = $req;
        
        aio_lstat( $file, $con->callback( 'stat_file', $file ) );

        return;
#        $r = HTTP::Response->new( 200 );
#        $r->content_type( 'text/plain' );
#        $r->content( ( 'x' x 2048 ) );
#        $r->header( 'content-length' => 2048 );
    } else {
        warn "request isn't an HTTP object";
        $r = HTTP::Response->new( 500 );
    }
    
#    print FH Data::Dumper->Dump([$req])."\n";
#    print FH "-------\n";
   
#   $con->{_close] = 1 if ( $con->{__requests} && $con->{__requests} > 100 );
   
    if ( $con->{_close} ) {
        $r->header( 'connection' => 'close' );
        $con->wheel->pause_input(); # no more requests
        $con->send( $r );
        $con->close();
    } else {
        # TODO set timeout based on keepalive: header
        $r->header( 'connection' => 'keep-alive' );
        $con->send( $r );
        delete $con->{_req};
        $con->{__requests}++;
    }

    return 1;
}
        
sub stat_file {
    my ( $self, $server, $con, $file ) = @_;
    
    unless ( -e _ ) {
        my $r = HTTP::Response->new( 200 );
        $r->content_type( 'text/plain' );
        my $out = '';

#        if ( delete $con->{_orig} ) {
#            delete $con->{_content_type};
#            $self->_log( v=> 4, msg => "200 [directory] $file" );
#            $out = 'directory browsing denied';
#        } else {
            $self->_log( v=> 4, msg => "404 $file" );
            $out = 'file not found';
#        }

        $r->header( 'content-length' => length($out) );
        $r->header( 'connection' => 'keep-alive' )
            if ( !$con->{_close} );
        $r->content( $out );
        $con->send( $r );
        delete $con->{_req};
        $con->close() if ( $con->{_close} );
        return;
    }

    delete $con->{_orig};
    
    if ( -d _ ) {
        my $r;
        if ( $con->{_req} && $con->{_req}->uri !~ m!/$! ) {
            my $uri = $con->{_req}->uri;
            $self->_log( v=> 4, msg => "302 [directory] $uri => $uri/" );
            $r = HTTP::Response->new( 302 );
            $r->header( 'Location' => $uri."/" );
            $r->header( 'content-length' => 0 );
            $r->header( 'connection' => 'keep-alive' )
                if ( !$con->{_close} );
            $con->send( $r );
            delete $con->{_req};
            $con->close() if ( $con->{_close} );
            return;
        }
       
        $self->_log( v=> 4, msg => "200 [directory] $file" );
        $r = HTTP::Response->new( 200 );
        $r->content_type( 'text/plain' );
        my $out = 'directory browsing denied';
        $r->header( 'content-length' => length($out) );
        $r->header( 'connection' => 'keep-alive' )
            if ( !$con->{_close} );
        $r->content( $out );
        $con->send( $r );
        delete $con->{_req};
        $con->close() if ( $con->{_close} );
        return;
    }

    my $size = -s _;
#    $self->_log( v=> 4, msg => "stat file $file size: $size" );

    aio_open( $file, O_RDONLY, 0, $con->callback( 'opened_file', $file, $size, delete $con->{_content_type} ) );
}

sub opened_file {
    my ( $self, $server, $con, $file, $size, $ct, $fh ) = @_;

    my $contents = '';
    aio_read( $fh, 0, $size, $contents, 0, sub {
        my $r = HTTP::Response->new( 200 );

        if ( $size == $_[0] ) {
            $self->_log( v=> 4, msg => "200 [$size] $file" );
            $r->content_type( $ct );
            $r->header( 'content-length' => $size );
        } else {
            $self->_log( v=> 4, msg => "500 [$size] [short read:$!] $file" );
            warn "short read: $!";
            $r = HTTP::Response->new( 500 );
            $r->content_type( 'text/plain' );
            $contents = 'ERROR: short read';
            $r->header( 'content-length' => length($contents) );
        }

        $r->header( 'connection' => 'keep-alive' )
            if ( !$con->{_close} );
        $r->content( $contents );
        delete $con->{_req};
        $con->send( $r );

        close $fh if ($fh);
        $con->close() if ( $con->{_close} );
        return;
    });
}

# private methods

sub resolve_path {
    my $self = shift;
    my $path = shift || '';

    my $cwd = '/';
    my $path_out = '';

    if ($path eq '') {
        $path_out = $cwd;
    } elsif ($path eq '/') {
        $path_out = '/';
    } else {
        my @real_ele = split(/\//, $cwd);
        if ($path =~ m/^\//) {
            undef @real_ele;
        }
        foreach (split(/\//, $path)) {
            if ($_ eq '..') {
                pop(@real_ele) if ($#real_ele);
            } elsif ($_ eq '.') {
                next;
            } elsif ($_ eq '~') {
                @real_ele = split(/\//, $self->home_path());
            } else {
                push(@real_ele, $_);
            }
        }
        $path_out = join('/', @real_ele);
    }
    
    $path_out = ( $path_out =~ m!^/! ) ? $path_out : '/'.$path_out;
    $path_out .= ( $path_out =~ m!/$! ) ? '' : '/';

    return $path_out;
}

our %content_types = (
    html => 'text/html',
    htm => 'text/html',
    css => 'text/css',
    txt => 'text/plain',
    xml => 'text/xml',
    js => 'text/javascript',
    ico => 'image/x-icon',
    jpg => 'image/jpeg',
    jpeg => 'image/jpeg',
    tif => 'image/tiff',
    tiff => 'image/tiff',
    png => 'image/png',
    gif => 'image/gif',
);

sub lookup_content_type {
    my ( $self, $file ) = @_;

    # TODO better content type detection
    if ( $file =~ m/\.(\S+)$/ ) {
        my $t = $content_types{lc( $1 )};
        return $t if ( $t );
    }

    return 'application/unknown';
}

1;
