package Sprocket::Plugin::HTTP::CGI;

use Sprocket qw( Plugin::HTTP );
use base 'Sprocket::Plugin::HTTP';

use POE qw( Filter::Line Filter::Stream );
use HTTP::Response;
use HTTP::Date;
use bytes;

use strict;
use warnings;

sub new {
    my $class = shift;
    
    my $self = $class->SUPER::new(
        name => 'HTTP::CGI',
        @_
    );

    return $self;
}

sub as_string {
    __PACKAGE__;
}


sub plugin_add {
    
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
    my $self = shift;
    my ( $server, $con, $req ) = @_;
    
    $self->start_http_request( @_ ) or return 1;
    
    $con->{_req} = $req;
    $con->{_r} ||= HTTP::Response->new( 200 );
    
    $con->{_file} = $self->resolve_path( $con->{_docroot}.$con->{_uri} );

    my $uri = $req->uri;
    if ( $uri =~ m!\?.+! ) {
        $con->{_params} = ( $uri =~ m/\?(.*)/o )[ 0 ];
        $uri =~ s/\?.*//o;
    }

    my $host = $req->header( 'Host' );
    my $env = {
        SCRIPT_NAME => $uri,
        SERVER_NAME => $host,
        SERVER_ADMIN => 'webmaster@'.$host,
        REQUEST_METHOD => $req->method(),
        # XXX https when we support it
        SCRIPT_URI => "http://$host".$uri,
        SCRIPT_FILENAME => $con->{_file},
        SERVER_SOFTWARE => 'Sprocket; Cometd',
        QUERY_STRING => $con->{_params} || '',
        REMOTE_PORT => $con->peer_port,
        SERVER_PORT => $server->{opts}->{listen_port},
#        SERVER_SIGNATURE => '',
        REMOTE_ADDR => $con->peer_ip,
        SERVER_PROTOCOL => $req->protocol(),
        PATH => '/usr/local/bin:/usr/bin:/bin',
        REQUEST_URI => $req->uri,
        GATEWAY_INTERFACE => 'CGI/1.1',
        SCRIPT_URL => $uri,
        # XXX wrong, can't be 0.0.0.0
        SERVER_ADDR => $server->{opts}->{listen_address},
        DOCUMENT_ROOT => $con->{_docroot},
    };
    
    $req->headers->scan( sub { $env->{ 'HTTP_'.uc( $_[ 0 ] ) } = $_[ 1 ]; } );
    
    my $wheel = $con->{_cgi_wheel} = POE::Wheel::Run->new(
        # Set the program to execute, and optionally some parameters.
        Program     => \&scrub_env,
        ProgramArgs => [ $con->{_file}, $env ],

        StdoutEvent => $con->event( 'mychild_stdout' ),
        StderrEvent => $con->event( 'mychild_stderr' ),
        ErrorEvent  => $con->event( 'mychild_error' ),
        CloseEvent  => $con->event( 'mychild_closed' ),
        
        StdinFilter  => POE::Filter::Line->new(),
        StdoutFilter => POE::Filter::Stream->new(),
        StderrFilter => POE::Filter::Line->new(),
    );
    
    if ( $poe_kernel->can( "sig_child" ) ) {
        # handler already in Sprocket
        $poe_kernel->sig_child( $wheel->PID() => 'sig_child' );
    } else {
        # XXX uuuuh
    }

    # TODO test POST compat
    if ( my $content = $req->content ) {
        $wheel->put( $req->content );
    }

    return 1;
}

sub scrub_env {
    my ( $file, $env ) = @_;
    
    delete @ENV{ keys %ENV };
    
    foreach ( keys %$env ) {
        $ENV{ $_ } = $env->{ $_ };
    }

    exec($file);
}


sub mychild_stdin {
    my ( $self, $server, $con, $in ) = @_;
}


sub mychild_stdout {
    my ( $self, $server, $con, $in ) = @_;

    # TODO better handling of STDOUT, nph perhaps
    $con->{_content} .= $in;
    
    return;
}


sub mychild_stderr {
    my ( $self, $server, $con, $in ) = @_;

    $self->_log( v => 4, msg => "STDERR from $con->{_uri}: $in" );
}


sub mychild_error {
    my ( $self, $server, $con, $errstr, $errnum ) = @_;

    my $r = $con->{_r};
    
    if ( $errnum != 0 ) {
        $r->code( 500 );
        delete $con->{_content};
        $self->finish( $con, 'cgi error:'.$errstr );
        
        return;
    } else {
        my $no_content = 0;
        if ( my $content = delete $con->{_content} ) {
            # parse headers from cgi app
            # fake the status line, we'll fix it below
            $r = $con->{_r} = HTTP::Response->parse( $con->{_req}->protocol." 200\n".$content );
            # correct the out size after parse
            $r->header( 'Content-Length' => length( $r->content ) );
        } else {
            $no_content = 1;
        }

        if ( my $status = $r->header( 'Status' ) ) {
            $r->header( 'Status' => undef );
            $r->code( $status );
        } else {
            $r->code( 200 );
        }

        # fix common mistake in cgis
        $r->code( 302 )
            if ( $r->header( 'Location' ) );
        
        # TODO better handling of no content from cgi
        if ( $r->code == 200 && !$r->content ) {
            $r->code( 500 );
            $self->finish( $con, 'ERROR no content from cgi' );
            
            return;
        }
    }
    
    $self->finish( $con );

    return;
}


sub mychild_closed {
    my ( $self, $server, $con ) = @_;
    
    delete @{$con}{qw( _cgi_wheel _content )};
}


sub finish {
    my $self = shift;
    my ( $con ) = @_;

    $self->SUPER::finish( @_ );

    unless ( $con->{_close} ) {
        # release control to other plugins
        if ( my $ff = delete $con->{_forwarded_from} ) {
            $con->plugin( $ff );
            return;
        }
        $self->release_connection( $con );
    }

    return;
}


1;
