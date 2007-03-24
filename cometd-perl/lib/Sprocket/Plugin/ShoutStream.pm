package Sprocket::Plugin::ShoutStream;

use Sprocket qw( Plugin Event );
use base 'Sprocket::Plugin';

use POE qw(
    Filter::Shoutcast
    Filter::Line
);

use Data::Dumper;

use bytes;
use strict;
use warnings;

sub new {
    my $class = shift;
    $class->SUPER::new(
        name => 'ShoutStream',
        @_
    );
}

sub as_string {
    __PACKAGE__;
}

sub add_plugin {
    my ( $self, $cli_srv ) = @_;
    warn "add_plugin: $cli_srv ".$cli_srv->isa( 'Sprocket::Client' );
    if ( $cli_srv->isa( 'Sprocket::Client' ) ) {
        foreach ( @{$self->{stream_list}} ) {
            my ( $secure, $host, $path ) = ( m{http(s)?://([^/]+)(/.*)} );
            my $port;
            ( $host, $port ) = ( $host =~ m{([^:]+)(?::(\d+))?} );
            $port = $port ? $port : ( $secure ? 443 : 80 );
            $cli_srv->_log( v => 4, msg => "Stream $_ is [$host] [$port] [$path]" );
            # Sprocket resolves hosts for us
            warn "connect to $host $port";
            my $con = $cli_srv->connect( $host, $port );
            $con->{_uri} = $path;
        }
    }
}

# ---------------------------------------------------------
# Client

sub remote_connected {
    my ( $self, $client, $con, $socket ) = @_;
    
    $self->take_connection( $con );

    # look for the http stream header first
    $con->{_header} = 1;

    $client->_log( v => 4, msg => 'connected:'.$con->peer_addr ); 

    # POE::Filter::Stackable object:
    $con->filter->push( $con->{_filter} = POE::Filter::Shoutcast->new() );

    $con->send(
        sprintf( "GET %s HTTP/1.0", $con->{_uri} ),
        "Host: ".$con->peer_hostname,
        "User-Agent: Sprocket",
        "Icy-MetaData: 1",
        "Accept: */*",
        "Connection: close",
        ""
    );
    
    return 1;
}

sub remote_receive {
    my ($self, $client, $con, $data) = @_;
    
    if ( $con->{_header} ) {
        warn "full header: $data";

        foreach my $d ( split( /\n/, $data ) ) {
            $con->{_header_len} += length( "$d\n" );
            $client->_log( v => 4, msg => "data: $d" );

            if ( $d =~ m/icy-metaint:\s*(\d+)/ ) {
                $con->{_metaint} = $1;
            }
            if ( $d =~ m/icy-br:\s*(\d+)/ ) {
                $con->{_bitrate} = $1;
            }
        }
        delete $con->{_header};

        $con->{_filter}->set_metaint( $con->{_metaint} );
        #$con->{_filter}->set_metaint( -1 );

    } else {
        $client->_log( v => 4, msg => "data: ".length( $data ) );
        if ( ref( $data ) ) {
            $data->{meta} =~ s/\0//g;

            open(FH,">>dump.txt");
            print FH "---\\\n".Data::Dumper->Dump([$data])."\n---/\n";
            close(FH);
            $client->_log( v => 4, msg => "data: $data->{meta}" );
        }
    }
    
    return 1;
}

1;
