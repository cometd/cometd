package Sprocket::Plugin::HTTP::Server;

use Sprocket qw( Plugin::HTTP );
use base 'Sprocket::Plugin::HTTP';
use POE;
use HTTP::Response;
use IO::AIO;
use Fcntl;
use HTTP::Date;
use HTML::Entities qw( encode_entities );
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

    $self->{time_out} ||= 300;

    die "a DocumentRoot is required for plugin $self"
        unless( $self->{document_root} );
    
    # remove trailing slash
    $self->{document_root} =~ s!/$!!;

    die "DocumentRoot $self->{document_root} doesn't exist, please create it"
        unless( -d $self->{document_root} );

    return $self;
}


sub as_string {
    __PACKAGE__;
}

# ---------------------------------------------------------
# server

# the default local_accept behavior is to accept
# an allow/deny plugin could be added here, for instance
sub local_accept {
    my ( $self, $server, $con, $socket ) = @_;
    
    #$con->reject();
    $con->accept();

    return OK;
}


sub local_connected {
    my ( $self, $server, $con, $socket ) = @_;
    
    $self->take_connection( $con );
    # POE::Filter::Stackable object:
    $con->filter->push( POE::Filter::HTTPD->new() );
    $con->filter->shift(); # pull off Filter::Stream
    # cut laggers off
    $con->set_time_out( 5 );

    return OK;
}


sub local_receive {
    my $self = shift;
    my ( $server, $con, $req ) = @_;
    
    # XXX delete _forwarded_from?
    delete @{$con}{qw( _docroot _req _r _uri _params _start_time _forwarded_from )};

    # XXX change _ keys to __? or move them to $con->{x}
    foreach ( keys %$con ) {
        delete $con->{$_} if ( m/^__/ );
    }
 
    $self->start_http_request( @_ ) or return OK;
    
    my ( $out, $r );

    my $uri = $req->uri;
    $con->{_params} = ( $uri =~ m!\?! ) ? ( $uri =~ s/\?(.*)//o )[ 0 ] : '';
    # TODO better way of passing this sort of stuff to a forwarded plugin
    $con->{_docroot} = $self->{document_root};
    $con->{_uri} = $self->resolve_path( $uri );
    $con->{_req} = $req;
    $r = $con->{_r} = HTTP::Response->new( 200 );
    
    $con->set_time_out( $self->{time_out} );
#    $con->set_time_out( undef );

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
    
    unless ( $server->{aio} ) {
        warn "IO::AIO is unavailable!, please install it to use the HTTP Server plugin";
        $con->call( simple_response => 403 => 'IO::AIO is unavailable, files cannot be served without it being installed' );
        return OK;
    }

    $con->call( serve_request => $self->{document_root}.$con->{_uri} );

    return OK;
}


sub serve_request {
    my ( $self, $server, $con, $file ) = @_;

    aio_stat( $file, $con->callback( 'stat_file', $file ) );

    return OK;
}

# define this or other plugins in this chain will get this 
sub local_disconnected {
    return OK;
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
        $con->call( simple_response => 404 );
        return;
    }

    if ( -d _ ) {
        if ( $con->{_uri} !~ m!/$! ) {
            my $uri = $con->{_uri};
            $con->call( simple_response => 301 => $uri."/" );
            return;
        }
        
        if ( $self->{no_directory_listing} ) {
            $con->call( simple_response => 403 => 'Directory Listing Denied' );
            return;
        } else {
            aio_stat( $file.$self->{index_file}, $con->callback( 'stat_index_file', $file ) );
        }
        return;
    }

    $con->{__stat} = [ stat( _ ) ];
    $con->{_r}->header( 'Last-Modified' => time2str( $con->{__stat}->[ 9 ] ) );
    # XXX
#    $con->{_r}->header( 'Expires' => time2str( time() + ( 24*60*60 ) ) );

    $self->open_file( $server, $con, $file );

    return;
}


sub stat_index_file {
    my ( $self, $server, $con, $file ) = @_;
    
    if ( -e _ ) {
        $file .= $self->{index_file};
        $con->{__stat} = [ stat( _ ) ];
        $con->{_r}->header( 'Last-Modified' => time2str( $con->{__stat}->[ 9 ] ) );
        # XXX
#        $con->{_r}->header( 'Expires' => time2str( time() + ( 24*60*60 ) ) );
        $self->open_file( $server, $con, $file );
    } else {
        if ( $con->{_req}->method eq 'HEAD' ) {
            $con->{_r}->content_type( 'text/html' );
            # content length? 
            $con->call( 'finish' );
        } else {
            aio_scandir( $file, 0, $con->callback( 'scanned_directory_listing' ) );
            #aio_readdir( $file, $con->callback( 'directory_listing' ) );
        }
    }

    return;
}


# XXX remove
sub directory_listing {
    my ( $self, $server, $con, $entries ) = @_;

    my $uri = $con->{_uri};
    $con->{_r}->content_type( 'text/html' );
    my $out = qq|<!DOCTYPE HTML PUBLIC "-//IETF//DTD HTML 2.0//EN">
<html><head><title>Index of |.encode_entities($uri).qq|</title><head><body>
    <h2>Index of |.encode_entities($uri).qq|</h2>\n<ul>\n|;
    $entries = [] unless ( ref $entries );
    unshift( @$entries, '..' );

    foreach ( @$entries ) {
        next if ( $self->{hide_dotfiles} && m/^\./ && $_ ne '..' );
        $out .= '<li>';
        if ( m/\.(jpg|gif|png|jpeg|bmp|ico|tiff?)$/ ) {
            $out .= '<img src="'.uri_escape($_).'" border="0"/>';
        }
        $out .= '<a href="'.uri_escape($_).'">'.encode_entities($_)."</a></li>\n";
    }

    $out .= qq|</ul>\n</body></html>|;

    $con->call( finish => $out );
    
    return;
}

sub scanned_directory_listing {
    my ( $self, $server, $con, $dirs, $files ) = @_;

    my $uri = $con->{_uri};
    $con->{_r}->content_type( 'text/html' );
    my $out = qq|<!DOCTYPE HTML PUBLIC "-//IETF//DTD HTML 2.0//EN">
<html><head><title>Index of |.encode_entities($uri).qq|</title><head><body>
    <h2>Index of |.encode_entities($uri).qq|</h2>\n<ul>\n|;
    $dirs ||= [];
    $files ||= [];
    unshift( @$dirs, '..' );

    foreach ( @$dirs ) {
        next if ( $self->{hide_dotfiles} && m/^\./ && $_ ne '..' );
        $out .= '<li>[dir] ';
        $out .= '<a href="'.uri_escape($_).'/">'.encode_entities($_)."/</a></li>\n";
    }
    foreach ( @$files ) {
        next if ( $self->{hide_dotfiles} && m/^\./ && $_ ne '..' );
        $out .= '<li>';
        if ( m/\.(jpg|gif|png|jpeg|bmp|ico|tiff?)$/ ) {
            $out .= '<img src="'.uri_escape($_).'" border="0"/> ';
        }
        $out .= '<a href="'.uri_escape($_).'">'.encode_entities($_)."</a></li>\n";
    }

    $out .= qq|</ul>\n</body></html>|;

    $con->call( finish => $out );
    
    return;
}

sub open_file {
    my ( $self, $server, $con, $file ) = @_;

    # bail if HEAD request
    if ( $con->{_req}->method eq 'HEAD' ) {
        my $r = $con->{_r};
        $r->content_type( 'text/html' );
        $r->header( 'Content-Length' => $con->{__stat}->[ 7 ] );
        $con->call( 'finish' );
        return;
    }

    # 304 check
    if ( my $since = $con->{_req}->header( 'If-Modified-Since' ) ) {
        $since = str2time( $since );
        my $mtime = $con->{__stat}->[ 9 ];
        if ( $mtime && $since && $since >= $mtime ) {
            $con->call( simple_response => 304 );
            return;
        }
    }

    aio_open( $file, O_RDONLY, 0, $con->callback( 'opened_file', $file ) );

    return;
}


sub opened_file {
    my ( $self, $server, $con, $file, $fh ) = @_;

    # call the send_file event in the superclass
    my $out = '';
    aio_read( $fh, 0, $con->{__stat}->[ 7 ], $out, 0, $con->callback( "send_file", $file, $fh, \$out ) );

    return;
}

1;
