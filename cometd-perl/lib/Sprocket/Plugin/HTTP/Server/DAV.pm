package Sprocket::Plugin::HTTP::Server::DAV;

use Sprocket qw( Plugin::HTTP::Server );
use base 'Sprocket::Plugin::HTTP::Server';
use POE;
use IO::AIO;
use XML::LibXML;
use HTTP::Date qw(time2str time2isoz);
use Encode;

use bytes;

use strict;
use warnings;

sub new {
    my $class = shift;
    
    my $self = $class->SUPER::new(
        name => 'DAV',
        impl => {
            options  => 1,
            get      => 1,
            head     => 1,
            propfind => 1,

            delete   => 1,
            put      => 1,
            copy     => 1,
            lock     => 1,
            unlock   => 1,
            move     => 1,

            post     => 1,
            trace    => 1,
            mkcol    => 1,
        },
        @_
    );

    return $self;
}


sub as_string {
    __PACKAGE__;
}

# ---------------------------------------------------------
# server

sub serve_request {
    my ( $self, $server, $con, $file ) = @_;

    $con->{__file} = $file;

    my $req = $con->{_req};
    my $method = lc $req->method;

    if ( $method && $self->{impl}->{ $method } && $self->can( "cmd_$method" ) ) {
       return $con->call( "cmd_$method" );
    } else {
        $server->_log( v => 4, msg => "$method not implemented" );
        $con->call( simple_response => 501 );
    }

    return 1;
}

*cmd_put = *denied;
*cmd_delete = *denied;
*cmd_copy = *denied;
*cmd_lock = *denied;
*cmd_unlock = *denied;
*cmd_move = *denied;

*cmd_post = *denied;
*cmd_trace = *denied;
*cmd_mkcol = *denied;

sub denied {
    my ( $self, $server, $con ) = @_;
    
    $con->call( simple_response => 403 => 'Permission Denied' );

    return;
}

sub cmd_options {
    my ( $self, $server, $con ) = @_;
    my $r = $con->{_r};

    $r->header( 'DAV' => '1,2,<http://apache.org/dav/propset/fs/1>' );
    $r->header( 'MS-Author-Via' => 'DAV' );
    $r->header( 'Allow'         => join( ',', map { $self->{impl} ? uc $_ : () } keys %{$self->{impl}} ) );
    $r->header( 'Content-Type'  => 'httpd/unix-directory' );
    $r->header( 'Keep-Alive'    => 'timeout=15, max=96' );

    $con->call( 'finish' => '' );

    return OK;
}

*cmd_head = *cmd_get;

sub cmd_get {
    my ( $self, $server, $con ) = @_;

    aio_stat( $con->{__file}, $con->callback( 'stat_file', $con->{__file} ) );

    return OK;
}

#PROPFIND / HTTP/1.1
#Content-Language: en-us
#Accept-Language: en-us
#Content-Type: text/xml
#Translate: f
#Depth: 0
#Content-Length: 0
#User-Agent: Microsoft Data Access Internet Publishing Provider DAV
#Host: cometd.net:4242
#Connection: Keep-Alive
#Cookie: client-id=4F892800-D89E-11DB-B72C-D6A8ABED4F66; auth-token=485759af11772a6970a7a909271a09b893f24a0c


sub cmd_propfind {
    my ( $self, $server, $con ) = @_;

    my ( $r, $req, $file ) = @{$con}{qw( _r _req __file )};

    $con->{__prop} = {
        # 0 or 1 or 1,noroot or infinite
        depth => $req->header( 'Depth' ),
        reqinfo => 'allprop',
        reqprops => [],
    };

    if ( $req->header( 'Content-Length' ) ) {
        my $parser  = XML::LibXML->new;
        my $doc;
        eval {
            $doc = $parser->parse_string( $req->content );
        };
        if ( $@ ) {
            $con->call( simple_response => 400 );
            return;
        }

        #$con->{_reqinfo} = doc->find('/DAV:propfind/*')->localname;
        $con->{__prop}->{reqinfo} = $doc->find('/*/*')->shift->localname;

        if ( $con->{__prop}->{reqinfo} eq 'prop' ) {
            #foreach my $node ($doc->find('/DAV:propfind/DAV:prop/*')) {
            foreach my $node ( $doc->find('/*/*/*')->get_nodelist ) {
                push( @{$con->{__prop}->{reqprops}}, [ $node->namespaceURI, $node->localname ] );
            }
        }
    }

    aio_stat( $file, $con->callback( 'stat_file_propget' ) );

    return;
}


sub stat_file_propget {
    my ( $self, $server, $con ) = @_;

    if ( !-e _ ) {
        $con->call( simple_response => 404 );
        return;
    }

    my ( $r, $req, $file, $prop ) = @{$con}{qw( _r _req __file __prop )};

    $r->code( 207 );
    $r->message( 'Multi-Status' );
    $r->header( 'Content-Type' => 'text/xml; charset="utf-8"' );

    if ( defined $prop->{depth} && $prop->{depth} eq '1' and -d _ ) {
        aio_scandir( $file, 0, $con->callback( 'scan_directory_propget' ) );
    } else {
        $con->{__paths} = [ $con->{_uri} ];
        $con->{__listing} = [];
        $con->{__stat_list} = { $con->{_uri} => [ (stat( _ ))[ 7, 9, 10 ], 1 ] };
        $con->call( 'stat_paths_done' );
    }

    return;
}

sub scan_directory_propget {
    my ( $self, $server, $con, $dirs, $files ) = @_;

    $dirs  ||= [];
    $files ||= [];

    my $p = $con->{_uri}; # dir
    $p .= '/' unless $p =~ m{/$};
    my @paths = map { $p.$_ } @$dirs, @$files;

    $con->{__listing} = [ $dirs, $files ];
    $con->{__paths} = [ @paths ]; # copy

    my $st = $con->{__stat_list} = {};

    my $grp = aio_group( $con->callback( 'stat_paths_done' ) );
    limit $grp 4;
    feed $grp sub {
       my $file = pop @paths
         or return;
       add $grp aio_stat $con->{_docroot}.$file, sub { $st->{$file} = [ (stat( _ ))[ 7, 9, 10 ], ( -d _ ) ] };
    };

    # XXX is there a better way to cache _ ?

    return;
}

sub stat_paths_done {
    my ( $self, $server, $con ) = @_;
    
    my $paths = $con->{__paths};

    my $doc = XML::LibXML::Document->new( '1.0', 'utf-8' );
    my $multistat = $doc->createElement( 'D:multistatus' );
    $multistat->setAttribute( 'xmlns:D', 'DAV:' );
    $doc->setDocumentElement( $multistat );

    for my $path ( @$paths ) {
        my ( $size, $mtime, $ctime, $isdir ) = @{$con->{__stat_list}->{ $path }};
        $mtime = time2str( $mtime );
        $ctime = time2isoz( $ctime );
        $ctime =~ s/ /T/;
        $ctime =~ s/Z//;
        $size ||= 0;

        my $resp = $doc->createElement('D:response');
        $multistat->addChild($resp);
        my $href = $doc->createElement('D:href');
        $href->appendText(
            File::Spec->catdir( map { uri_escape encode_utf8 $_ } File::Spec->splitdir( $path ) )
        );
        $resp->addChild($href);
        $href->appendText( '/' ) if $isdir;
        my $ok = $doc->createElement('D:prop');
        my $nf = $doc->createElement('D:prop');
        my $prop;

        my $add_ok = sub {
            my $tmp = $doc->createElement( $_[0] );
            $tmp->appendText( $_[1] ) if ( defined $_[1] );
            $ok->addChild( $tmp );
        };

        if ( $con->{__prop}->{reqinfo} eq 'prop' ) {
            my %prefixes = ( 'DAV:' => 'D' );
            my $i = 0;

            for my $reqprop ( @{$con->{__prop}->{reqprops}} ) {
                my ( $ns, $name ) = @$reqprop;

                if ( $ns eq 'DAV:' && $name eq 'creationdate' ) {
                    $add_ok->( 'D:creationdate', $ctime );
                } elsif ( $ns eq 'DAV:' && $name eq 'getcontentlength' ) {
                    $add_ok->( 'D:getcontentlength', $size );
                } elsif ( $ns eq 'DAV:' && $name eq 'getcontenttype' ) {
                    $add_ok->( 'D:getcontenttype', ( $isdir ) ? 'httpd/unix-directory' : 'httpd/unix-file' );
                } elsif ($ns eq 'DAV:' && $name eq 'getlastmodified') {
                    $add_ok->( 'D:getlastmodified', $ctime );
                } elsif ($ns eq 'DAV:' && $name eq 'resourcetype') {
                    $prop = $doc->createElement('D:resourcetype');
                    if ( $isdir ) {
                        $prop->addChild( $doc->createElement('D:collection') );
                    }
                    $ok->addChild($prop);
                } else {
                    my $prefix = $prefixes{$ns};
                    if (!defined $prefix) {
                        $prefix = 'i'.$i++;
                        $resp->setAttribute( "xmlns:$prefix", $ns );
                        $prefixes{$ns} = $prefix;
                    }

                    $prop = $doc->createElement( "$prefix:$name" );
                    $nf->addChild( $prop );
                }
            }

        } elsif ( $con->{__prop}->{reqinfo} eq 'propname' ) {
            foreach ( qw( creationdate getcontentlength getcontenttype getlastmodified resourcetype )) {
                $add_ok->( 'D:'.$_ );
            }
        } else {
            $add_ok->( 'D:creationdate', $ctime );
            $add_ok->( 'D:getcontentlength', $size );
            $add_ok->( 'D:getcontenttype', ( $isdir ) ? 'httpd/unix-directory' : 'httpd/unix-file' );
            $add_ok->( 'D:getlastmodified', $mtime );

            $prop = $doc->createElement('D:supportedlock');
            foreach (qw( exclusive shared )) {
                my $lock = $doc->createElement('D:lockentry');
                my $scope = $doc->createElement('D:lockscope');
                my $attr  = $doc->createElement('D:'.$_);
                $scope->addChild($attr);
                $lock->addChild($scope);
                my $type = $doc->createElement('D:locktype');
                $attr = $doc->createElement('D:write');
                $type->addChild($attr);
                $lock->addChild($type);
                $prop->addChild($lock);
            }
            $ok->addChild($prop);

            $prop = $doc->createElement('D:resourcetype');
            $prop->addChild( $doc->createElement('D:collection') )
               if ( $isdir );
            $ok->addChild($prop);
        }

        if ( $ok->hasChildNodes ) {
            my $propstat = $doc->createElement('D:propstat');
            $propstat->addChild($ok);
            my $stat = $doc->createElement('D:status');
            $stat->appendText('HTTP/1.1 200 OK');
            $propstat->addChild($stat);
            $resp->addChild($propstat);
        }

        if ( $nf->hasChildNodes ) {
            my $propstat = $doc->createElement('D:propstat');
            $propstat->addChild($nf);
            my $stat = $doc->createElement('D:status');
            $stat->appendText('HTTP/1.1 404 Not Found');
            $propstat->addChild($stat);
            $resp->addChild($propstat);
        }
    }

    $con->call( finish => $doc->toString( 1 ) );
    return;
}

1;
