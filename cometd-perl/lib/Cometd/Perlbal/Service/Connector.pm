package Cometd::Perlbal::Service::Connector;

use strict;
use warnings;
no warnings qw( deprecated ); # fields uses pseudohashes

use base qw( Perlbal::Socket );
use fields qw( service buffer mode filter );

use POE::Filter::Stackable;
use POE::Filter::Line;
use POE::Filter::JSON;
use Scalar::Util qw( weaken isweak );
use JSON;

sub MODE_NA      { 0 }
sub MODE_LINE    { 1 }
sub MODE_HTTP    { 2 }
sub MODE_BAYEUX  { 3 }

our %MODES = (
    MODE_NA() => 'NA',
    MODE_LINE() => 'LINE',
    MODE_HTTP() => 'HTTP',
    MODE_BAYEUX() => 'BAYEUX',
);

our @socket_list;

sub new {
    my ($class, $service, $sock) = @_;
    my $self = $class->SUPER::new($sock);

    $self->{service} = $service;
    $self->{buffer} = '';
    $self->{mode} = MODE_NA;
    $self->{filter} = POE::Filter::Stackable->new(
        Filters => [
            POE::Filter::Line->new()
        ]
    );
    
    bless $self, ref $class || $class;
    
    $self->watch_read( 1 );
    
    @socket_list = grep { defined } ( @socket_list, $self );
    foreach (@socket_list) { weaken( $_ ); }
    
    return $self;
}

sub event_read {
    my Cometd::Perlbal::Service::Connector $self = shift;

    my $ref;
    if ( $self->{mode} == MODE_NA ) {
        $ref = $self->read( 1024 );
        return $self->close() unless defined $ref;
        $self->{buffer} .= $$ref;

        if ( $self->{buffer} =~ /^(?:HEAD|GET|POST) / ) {
            $self->{mode} = MODE_HTTP;
            $self->{headers_string} .= $$ref;
        }
    }

    if ( $self->{mode} == MODE_HTTP ) {
        my $hd = $self->read_request_headers();
        return unless $hd;
        # TODO POST
        # TODO status html
        $self->handle_http_req();
        return;
    } elsif ( $self->{mode} == MODE_NA ) {
        $self->{mode} = MODE_LINE;
    }

    unless ( ref( $ref ) ) {
        $self->{buffer} = '';
        $ref = $self->read( 1024 );
        return $self->close() unless defined $ref;
    }

    my $lines = $self->{filter}->get( [ $$ref ] );

    while ( my $line = shift @$lines ) {
        if ( $self->{mode} == MODE_BAYEUX ) {
            # $line in this mode is a json event obj
            #require Data::Dumper;
            #$self->write( "cometd:bayeux> ".Data::Dumper->Dump( [ $line ] )."\r\n" );
            Cometd::Perlbal::Service::handle_event( $line ); # 
            next;
        }
        
        if ( $self->{mode} == MODE_LINE
            && ( $line =~ m/^bayeux (\d+)(\.\d+)?/ || $line =~ m/^[\{|\[]/ ) ) {
                #my ($major, $minor) = ($1,$2);
                $self->{mode} = MODE_BAYEUX;
                
                my $filter = POE::Filter::JSON->new();
                unshift( @$lines, $line ) if ( $line =~ m/^[\{|\[]/ );
                
                # TODO bayeux filter based on filter::json
                $self->{filter}->push( $filter );
                
                $lines = $filter->get( $lines );
                
                next;
        }
       
        
        if ( $line =~ /^quit|exit$/i ) {
            $self->close( 'user_requested_quit' );
            return;
        }

        if ( $line =~ m/^list sockets/i ) {
            
            $self->write("> # : mode socket\r\n");
            my $num = 0;
            foreach (@socket_list) {
                next unless defined;
                $num++;
                $self->write("> $num : ".$MODES{ $_->{mode} }." $_ weak ref:".( isweak($_) ? 1 : 0 )."\r\n");
            }
            $self->write("> total: $num\r\n");
            next;
        }

        if ( $line =~ s/^bcast (.*)\s+?$/$1/i ) {
            my $num = __PACKAGE__->multiplex_send( $line );
            $self->write("> sent: '$line' to $num clients.\r\n");
            next;
        }
        
        $self->write( "cometd:manage> unknown command:$line\r\n" );
    }

    return;
}

sub event_write {
    my $self = shift;

    if ( $self->{mode} == MODE_BAYEUX ) {
        my $lines = $self->{filter}->put( [] );
        foreach (@$lines) {
            warn "writing data: $_";
            $self->write( $_ );
        }
    }
    warn "writing on socket $self in mode $self->{mode}\n";
    $self->watch_write( 0 ) if $self->write( undef );
}

sub event_err {
    my $self = shift;
    $self->close;
}

sub event_hup {
    my $self = shift;
    $self->close;
}

sub handle_http_req {
    my Cometd::Perlbal::Service::Connector $self = shift;
    my $uri = $self->{req_headers}->request_uri;
    my $code = '200 OK';
    my $body;

    # TODO commands, status, list connected clients and channels
    if ($uri eq '/') {
        $body .= '<h1>Cometd Perlbal Service</h1>';
        $body .= 'Visit the <a href="http://cometd.com/">Cometd</a> website for details';
    } else {
        $code = '404 Not found';
        $body .= "<h1>$code</h1>";
    }

    #$body .= '<hr style='margin-top: 10px' /><a href='/'>Cometd Perlbal Service</a>.\n';
    $self->write( "HTTP/1.0 $code\r\nContent-type: text/html\r\nContent-Length: "
                  .length($body)."\r\n\r\n$body" );
    $self->write( sub { $self->close; } );
    return;
}

sub multiplex_send {
    my $data = $_[ 1 ];
    
    my $num = 0;
    my $obj;
    if ( ref( $data ) eq 'HASH' || ref( $data ) eq 'ARRAY' ) {
        $obj = $data;
    } else {
        $obj = eval { jsonToObj( $data ); };
        if ($@) {
            warn $@;
            return 0;
        }
    }
    
    # cleanup socket list
    foreach (@socket_list) {
        next unless ( $_->{mode} == MODE_BAYEUX );
        $num++;
        warn "sending $data which is $obj\n";
        my $lines = $_->{filter}->put( [ $obj ] );
        foreach my $blk (@$lines) {
            $_->write( $blk );
        }
    }
    
    return $num;
}

1;
