package Sprocket::Plugin::HTTP::Server;

use Sprocket qw( Plugin::HTTP );
use base 'Sprocket::Plugin::HTTP';
use POE;
use HTTP::Response;
use IO::AIO;
use Fcntl;
use HTTP::Date;
use Time::HiRes qw( time );
use bytes;

use strict;
use warnings;

sub new {
    my $class = shift;
    
    my $self = $class->SUPER::new(
        name => 'HTTP',
        index_file => 'index.html',
        home_path => 'home',
        hide_dotfiles => 1,
        @_
    );

    die "a DocumentRoot is required for plugin $self"
        unless( $self->{document_root} );
    
    # remove trailing slash
    $self->{document_root} =~ s!/$!!;

    die "DocumentRoot $self->{document_root} doesn't exist, please create it"
        unless( -d $self->{document_root} );

    return $self;
}


sub add_plugin {
    my ( $self, $server ) = @_;
   
    return undef;
}


sub as_string {
    __PACKAGE__;
}

# ---------------------------------------------------------
# server

sub local_accept {
    my ( $self, $server, $con, $socket ) = @_;
    
    unless ( $server->{aio} ) {
        warn "IO::AIO is unavailable!, please install it to use the HTTP Server plugin";
        $con->reject();
        return 1;
    }

    $con->accept();

    return 1;
}

sub local_connected {
    my ( $self, $server, $con, $socket ) = @_;
    
    $self->take_connection( $con );
    # POE::Filter::Stackable object:
    $con->filter->push( POE::Filter::HTTPD->new() );
    $con->filter->shift(); # pull off Filter::Stream
    # cut laggers off
    $con->set_time_out( 5 );
    return 1;
}


sub local_receive {
    my $self = shift;
    my ( $server, $con, $req ) = @_;
    
    # XXX delete _forwarded_from?
    delete @{$con}{qw( _docroot _req _r _uri _params _stat _start_time _forwarded_from )};
    
    # XXX debug check for keys that begin with _
 
    $self->start_http_request( @_ ) or return 1;
    
    my ( $out, $r );

    my $uri = $req->uri;
    $con->{_params} = ( $uri =~ m!\?! ) ? ( $uri =~ s/\?(.*)//o )[ 0 ] : '';
    # TODO better way of passing this sort of stuff to a forwarded plugin
    $con->{_docroot} = $self->{document_root};
    $con->{_uri} = $self->resolve_path( $uri );
    $con->{_req} = $req;
    $r = $con->{_r} = HTTP::Response->new( 200 );
    
#    $con->set_time_out( 300 );
    $con->set_time_out( undef );

    if ( $self->{forward_list} ) {
        foreach my $regex ( keys %{ $self->{forward_list} } ) {
            my $name = $self->{forward_list}->{ $regex };
            # XXX should I use the resolved_path uri or request uri
            if ( $con->{_uri} =~ /$regex/ ) {
                $con->{_forwarded_from} = $con->plugin(); # XXX or $self->as_string()?

                if ( my $ret = $server->forward_plugin( $name, $server, $con, $req ) ) {
                    return $ret;
                } else {
                    $server->_log( v => 4, msg => 'skipped plugin forward to '.$name.' on uri '.$con->{_uri}." (return value:$ret)");
                    next;
                }
            } 
        }
        # didn't forward request
        delete $con->{_forwarded_from};
    }
 
    my $file = $self->{document_root}.$con->{_uri};
    aio_stat( $file, $con->callback( 'stat_file', $file ) );

    return 1;
}


# 0 dev      device number of filesystem
# 1 ino      inode number
# 2 mode     file mode  (type and permissions)
# 3 nlink    number of (hard) links to the file
# 4 uid      numeric user ID of file's owner
# 5 gid      numeric group ID of file's owner
# 6 rdev     the device identifier (special files only)
# 7 size     total size of file, in bytes
# 8 atime    last access time in seconds since the epoch
# 9 mtime    last modify time in seconds since the epoch
# 10 ctime   inode change time in seconds since the epoch (*)
# 11 blksize preferred block size for file system I/O
# 12 blocks  actual number of blocks allocated


sub stat_file {
    my ( $self, $server, $con, $file ) = @_;
    
    unless ( -e _ ) {
        $con->{_r}->content_type( 'text/plain' );
        $con->call( finish => 'file not found' );
        return;
    }

    if ( -d _ ) {
        if ( $con->{_uri} !~ m!/$! ) {
            my $uri = $con->{_uri};
            my $r = $con->{_r};
            $r->code( 301 );
            $r->header( 'Location' => $uri."/" );
            $con->call( finish => '' );
            return;
        }
        
        if ( $self->{no_directory_listing} ) {
            $con->{_r}->content_type( 'text/plain' );
            $con->call( finish => 'Directory Listing Denied' );
        } else {
            aio_stat( $file.$self->{index_file}, $con->callback( 'stat_index_file', $file ) );
        }
        return;
    }

    $con->{_stat} = [ stat( _ ) ];
    $con->{_r}->header( 'Last-Modified' => time2str( $con->{_stat}->[ 9 ] ) );
    # XXX
#    $con->{_r}->header( 'Expires' => time2str( time() + ( 24*60*60 ) ) );

    $self->open_file( $server, $con, $file );

    return;
}


sub stat_index_file {
    my ( $self, $server, $con, $file ) = @_;
    
    if ( -e _ ) {
        $file .= $self->{index_file};
        $con->{_stat} = [ stat( _ ) ];
        $con->{_r}->header( 'Last-Modified' => time2str( $con->{_stat}->[ 9 ] ) );
        # XXX
#        $con->{_r}->header( 'Expires' => time2str( time() + ( 24*60*60 ) ) );
        $self->open_file( $server, $con, $file );
    } else {
        if ( $con->{_req}->method eq 'HEAD' ) {
            $con->{_r}->content_type( 'text/html' );
            # content length? 
            $con->call( 'finish' );
        } else {
            aio_readdir( $file, $con->callback( 'directory_listing' ) );
        }
    }

    return;
}


sub directory_listing {
    my ( $self, $server, $con, $entries ) = @_;

    my $uri = $con->{_uri};
    $con->{_r}->content_type( 'text/html' );
    my $out = qq|<html><head><title>Index of $uri</title><head><body>
    <h2>Index of $uri</h2>\n<ul>\n|;
    $entries = [] unless ( ref $entries );
    unshift( @$entries, '..' );
    foreach ( @$entries ) {
        next if ( $self->{hide_dotfiles} && m/^\./ && $_ ne '..' );
        $out .= qq|<li><a href="$_">$_</a></li>\n|;
    }
    $out .= qq|</ul>\n</body></html>|;

    $con->call( finish => $out );
    
    return;
}


sub open_file {
    my ( $self, $server, $con, $file ) = @_;

    # 304 check
    if ( my $since = $con->{_req}->header( 'If-Modified-Since' ) ) {
        $since = str2time( $since );
        my $mtime = $con->{_stat}->[ 9 ];
        if ( $mtime && $since && $since >= $mtime ) {
            $con->{_r}->code( 304 );
            $con->call( finish => '' );
            return;
        }
    }
    
    # bail if HEAD request
    if ( $con->{_req}->method eq 'HEAD' ) {
        my $r = $con->{_r};
        $r->content_type( 'text/html' );
        $r->header( 'Content-Length' => $con->{_stat}->[ 7 ] );
        $con->call( 'finish' );
        return;
    }

    aio_open( $file, O_RDONLY, 0, $con->callback( 'opened_file', $file ) );

    return;
}


sub opened_file {
    my ( $self, $server, $con, $file, $fh ) = @_;

    # call the send_file event in the superclass
    my $out = '';
    aio_read( $fh, 0, $con->{_stat}->[ 7 ], $out, 0, $con->callback( "send_file", $file, $fh, \$out ) );

    return;
}

1;
