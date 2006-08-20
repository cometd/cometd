package Cometd::Perlbal::Service::Connector;

use strict;
use warnings;
no warnings qw( deprecated ); # fields uses pseudohashes

use base qw( Perlbal::Socket );
use fields qw( service buffer is_http );

BEGIN {
    Perlbal::Service::add_role( cometd => sub {
        __PACKAGE__->new(@_);
    });
};

sub new {
    my ($class, $service, $sock) = @_;
    my $self = $class->SUPER::new($sock);

    $self->{service} = $service;
    $self->{buffer} = '';

    bless $self, ref $class || $class;
    
    $self->watch_read( 1 );
    return $self;
}

sub event_read {
    my Cometd::Perlbal::Service::Connector $self = shift;

    my $ref;
    unless ( $self->{is_http} ) {
        $ref = $self->read( 1024 );
        return $self->close() unless defined $ref;
        $self->{buffer} .= $$ref;

        if ($self->{buffer} =~ /^(?:HEAD|GET|POST) /) {
            $self->{is_http} = 1;
            $self->{headers_string} .= $$ref;
        }
    }

    if ($self->{is_http}) {
        my $hd = $self->read_request_headers;
        return unless $hd;
        $self->handle_http_req();
        return;
    }

    while ( $self->{buffer} =~ s/^(.+?)\r?\n// ) {
        my $line = $1;

        if ( $line =~ /^quit|exit$/ ) {
            $self->close( 'user_requested_quit' );
            return;
        }

        $self->write( "$line\r\n" );
    }
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
        $body .= '<h1>Perlbal Cometd Service </h1>';
        $body .= 'Visit the <a href="http://cometd.com/">Cometd</a> website for details';
    } else {
        $code = '404 Not found';
        $body .= "<h1>$code</h1>";
    }

    #$body .= '<hr style='margin-top: 10px' /><a href='/'>Perlbal Cometd Service</a>.\n';
    $self->write( "HTTP/1.0 $code\r\nContent-type: text/html\r\nContent-Length: "
                  .length($body)."\r\n\r\n$body" );
    $self->write( sub { $self->close; } );
    return;
}

1;
