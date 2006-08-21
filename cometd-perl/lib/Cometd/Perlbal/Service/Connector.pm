package Cometd::Perlbal::Service::Connector;

use strict;
use warnings;
no warnings qw( deprecated ); # fields uses pseudohashes

use base qw( Perlbal::Socket );
use fields qw( service buffer mode filter );

use POE::Filter::Stackable;
use POE::Filter::Line;
use POE::Filter::JSON;

BEGIN {
    Perlbal::Service::add_role( cometd => sub { __PACKAGE__->new( @_ ); });
};

sub MODE_NA      { 0 }
sub MODE_LINE    { 1 }
sub MODE_HTTP    { 2 }
sub MODE_BAYEUX  { 3 }

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
        # TODO support POST of json
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
            #require Data::Dumper;
            #$self->write( "cometd:bayeux> ".Data::Dumper->Dump( [ $line ] )."\r\n" );
            Cometd::Perlbal::Service::handle_event($line);
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
       
        
        if ( $line =~ /^quit|exit$/ ) {
            $self->close( 'user_requested_quit' );
            return;
        }
        
        $self->write( "cometd:manage> $line\r\n" );
    }

    return;
}

sub event_write {
    my $self = shift;
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

1;
