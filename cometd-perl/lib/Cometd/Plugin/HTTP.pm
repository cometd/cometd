package Cometd::Plugin::HTTP;

use Cometd::Plugin;
use base 'Cometd::Plugin';

use POE qw( Filter::HTTPD Filter::Stream Wheel::ReadWrite Driver::SysRW );
use HTTP::Response;
use IO::AIO;
use Fcntl;
use HTTP::Date;
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
    
    delete @{$con}{qw( _req _r _uri )};
    
    $con->{_close} = 1;

    if ( ref $req && $req->isa( 'HTTP::Response' ) ) {
        $r = $req; # a prebuilt response
        $con->{_close} = 1; # default anyway
    } elsif ( ref $req && $req->isa( 'HTTP::Request' ) ) {
        my $connection = $req->header( 'connection' );
        $con->{_close} = 0 if ( $connection && $connection =~ m/^keep-alive$/i );
        
        $con->{_uri} = $self->resolve_path( $req->uri );
        
        $con->wheel->pause_input(); # no more requests until ready
        
        $con->{_req} = $req;
        $r = $con->{_r} = HTTP::Response->new( 200 );
        $r->header( Date => time2str( time() ) );
        $r->header( Server => 'Cometd (http://cometd.com/)' );
        $r->header( Connection => ( $con->{_close} ) ? 'close' : 'keep-alive' );
        
        if ( $self->{forward_list} ) {
            foreach my $qr ( keys %{ $self->{forward_list} } ) {
                my $name = $self->{forward_list}->{ $qr };
                if ( $con->{_uri} =~ $qr ) {
                    if ( my $ret = $server->forward_plugin( $name, $server, $con, $req ) ) {
                        return $ret;
                    } else {
                        $self->_log( v => 4, msg => 'skipped plugin forward to '.$name );
                        next;
                    }
                } 
            }
        }

        my $file = $self->{document_root}.$con->{_uri};

        aio_lstat( $file, $con->callback( 'stat_file', $file ) );

        return 1;
    } else {
        warn "request isn't an HTTP object";
        $r = HTTP::Response->new( 500 );
    }
    
    # XXX max requests
#    $con->{_close} = 1 if ( $con->{__requests} && $con->{__requests} > 100 );
   
    if ( $con->{_close} ) {
        $r->header( 'connection' => 'close' );
        $con->send( $r );
        $con->wheel->pause_input(); # no more requests
        $con->close();
    } else {
        # TODO set timeout based on keepalive: header
        $r->header( 'connection' => 'keep-alive' );
        $con->send( $r );
        $con->{__requests}++;
    }

    return 1;
}
        
sub stat_file {
    my ( $self, $server, $con, $file ) = @_;
    
    unless ( -e _ ) {
        my $r = delete $con->{_r};
        $r->content_type( 'text/plain' );
        $self->_log( v=> 4, msg => "404 $file" );
        my $out = 'file not found';
        $r->header( 'content-length' => length($out) );
        $r->content( $out );
        $con->send( $r );
        $con->wheel->resume_input();
        $con->close() if ( $con->{_close} );
        return;
    }

    if ( -d _ ) {
        my $r;
        if ( $con->{_uri} !~ m!/$! ) {
            my $uri = $con->{_uri};
            $self->_log( v=> 4, msg => "302 [directory] $uri => $uri/" );
            $r = delete $con->{_r};
            $r->code( 302 );
            $r->header( 'Location' => $uri."/" );
            $r->header( 'content-length' => 0 );
            $con->send( $r );
            $con->wheel->resume_input();
            $con->close() if ( $con->{_close} );
            return;
        }
        
        if ( $self->{no_directory_browsing} ) {
            $self->_log( v=> 4, msg => "200 [directory] $file" );
            $r = delete $con->{_r};
            $r->content_type( 'text/plain' );
            my $out = 'directory browsing denied';
            $r->header( 'content-length' => length($out) );
            $r->content( $out );
            $con->send( $r );
            $con->wheel->resume_input();
            $con->close() if ( $con->{_close} );
        } else {
            aio_lstat( $file.$self->{index_file}, $con->callback( 'stat_file_index', $file ) );
        }
        return;
    }

    my $size = -s _;
    my $mtime = ( stat( _ ) )[ 9 ];
    $con->{_r}->header( 'Last-Modified' => time2str( $mtime ) );

    $self->serve_file( $server, $con, $file, $size, $mtime );
    return;
}


sub stat_file_index {
    my ( $self, $server, $con, $file ) = @_;
    
    if ( -e _ ) {
        $file .= $self->{index_file};
        my $size = -s _;
        my $mtime = ( stat( _ ) )[ 9 ];
        $con->{_r}->header( 'Last-Modified' => time2str( $mtime ) );

        $self->serve_file( $server, $con, $file, $size, $mtime );
    } else {
        if ( $con->{_req}->method eq 'HEAD' ) {
            my $r = delete $con->{_r};
            $r->content_type( 'text/html' );
            # content length? 
            $con->send( $r );
            $con->wheel->resume_input();
            $con->close() if ( $con->{_close} );
        } else {
            aio_readdir( $file, $con->callback( 'directory_listing' ) );
        }
    }

    return;
}


sub serve_file {
    my ( $self, $server, $con, $file, $size, $mtime ) = @_;

    # 304 check
    if ( my $since = $con->{_req}->header( 'if-modified-since' ) ) {
        $since = str2time( $since );
        if ( $since >= $mtime ) {
            $self->_log( v=> 4, msg => "304 [not modified] $file" );
            my $r = delete $con->{_r};
            $r->code( 304 );
            $r->header( 'content-length' => 0 );
            $con->send( $r );
            $con->wheel->resume_input();
            $con->close() if ( $con->{_close} );
            return;
        }
    }
    
    # bail if HEAD request
    if ( $con->{_req}->method eq 'HEAD' ) {
        my $r = delete $con->{_r};
        $r->content_type( 'text/html' );
        $r->header( 'content-length' => $size );
        $con->send( $r );
        $con->wheel->resume_input();
        $con->close() if ( $con->{_close} );
        return;
    }

    aio_open( $file, O_RDONLY, 0, $con->callback( 'opened_file', $file, $size ) );
    return;
}

sub directory_listing {
    my ( $self, $server, $con, $entries ) = @_;

    my $uri = $con->{_uri};
    my $r = delete $con->{_r};
    my $out = qq|<html><head><title>Index of $uri</title><head><body>
    <h2>Index of $uri</h2>|;
    if ( ref $entries ) {
        $out .= qq|<ul>\n|;
        foreach ( @$entries ) {
            $out .= qq|<li><a href="$_">$_</a></li>\n|;
        }
        $out .= qq|</ul>\n|
    } else {
        $out .= 'no files';
    }
    $out .= qq|</body></html>|;
    
    $r->content_type( 'text/html' );
    $r->header( 'content-length' => length($out) );
    $r->content( $out );
    $con->send( $r );
    $con->wheel->resume_input();
    $con->close() if ( $con->{_close} );
    return;
}


sub opened_file {
    my ( $self, $server, $con, $file, $size, $fh ) = @_;

    my $out = '';
    aio_read( $fh, 0, $size, $out, 0, sub {
        my $r = delete $con->{_r};

        if ( $size == $_[0] ) {
            $self->_log( v=> 4, msg => "200 [$size] $file" );
            $r->content_type( $self->lookup_content_type( $file ) );
            $r->header( 'content-length' => $size );
        } else {
            $self->_log( v=> 4, msg => "500 [$size] [short read:$!] $file" );
            warn "short read: $!";
            $r->code( 500 );
            $r->content_type( 'text/plain' );
            $out = 'ERROR: short read';
            $r->header( 'content-length' => length( $out ) );
        }

        $r->content( $out );
        $con->send( $r );

        close $fh if ($fh);
        $con->wheel->resume_input();
        $con->close() if ( $con->{_close} );
        return;
    });

    return;
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
    $path_out .= ( $path_out =~ m!/$! ) ? '' : '/'
        if ( $path =~ m!/$! );

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
    if ( $file =~ m/\.([^\.]+)$/ ) {
        my $t = $content_types{lc( $1 )};
        return $t if ( $t );
    }

    return 'application/unknown';
}

1;
