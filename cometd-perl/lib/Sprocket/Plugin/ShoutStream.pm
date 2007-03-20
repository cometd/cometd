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

# ---------------------------------------------------------
# Client

sub remote_connected {
    my ( $self, $client, $con, $socket ) = @_;
    
    $self->take_connection( $con );

    # look for the http stream header first
    $con->{_header} = 1;

    $client->_log( v => 4, msg => 'connected:'.$con->peer_addr ); 

    #$con->filter->push( POE::Filter::Line->new() );
    $con->filter->push( $con->{_filter} = POE::Filter::Shoutcast->new() );

    my $req = $self->{stream_list}->{$con->peer_addr};

    $con->send(
#        "GET / HTTP/1.0",
        "GET $req HTTP/1.0",
        sprintf( "Host: %s", $con->{peer_ip} ),
        "User-Agent: Sprocket",
        "Icy-MetaData: 1",
        "Accept: */*",
        "Connection: close",
        ""
    );
    
    
    # POE::Filter::Stackable object:
    
    return 1;
}

sub remote_receive {
    my ($self, $client, $con, $data) = @_;
    
    if ( $con->{_header} ) {
        warn "full data: $data";
        foreach my $d ( split( /\n/, $data ) ) {
            $con->{_header_len} += length( "$d\n" );
            $client->_log( v => 4, msg => "data: $d" );

            if ( $d =~ m/icy-metaint:\s*(\d+)/ ) {
                $con->{_metaint} = $1;
            }
            if ( $d =~ m/icy-br:\s*(\d+)/ ) {
                $con->{_bitrate} = $1;
            }
#            return unless ( $d eq '' );
        }
        delete $con->{_header};

        $con->{_filter}->set_metaint( $con->{_metaint} );

#        $con->filter->push( POE::Filter::Shoutcast->new(
#            in_metaint => $con->{_metaint},
#            header_len => $con->{_header_len},
#            blocksize => ( $con->{_metaint} ? $con->{_metaint} * 2 : 4096 ),
#        ) );
#        $con->filter->shift(); # POE::Filter::Line

        
    } else {
        $client->_log( v => 4, msg => "data: ".length( $data ) );
        if ( ref( $data ) ) {
            open(FH,">>dump.txt");
            print FH "---\\\n".Data::Dumper->Dump([$data])."\n---/\n";
            close(FH);
            #$client->_log( v => 4, msg => "data: ".Data::Dumper->Dump([$d]) );
        }
    }
    
    return 1;
}

1;
