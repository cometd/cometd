# $Id: HTTPD.pm 2155 2006-11-12 07:01:34Z teknikill $

# Filter::HTTPD Copyright 1998 Artur Bergman <artur@vogon.se>.

# Thanks go to Gisle Aas for his excellent HTTP::Daemon.  Some of the
# get code was copied out if, unfortunately HTTP::Daemon is not easily
# subclassed for POE because of the blocking nature.

# 2001-07-27 RCC: This filter will not support the newer get_one()
# interface.  It gets single things by default, and it does not
# support filter switching.  If someone absolutely needs to switch to
# and from HTTPD filters, they should submit their request as a patch.

package POE::Filter::HTTPD;

use strict;
use POE::Filter;

use vars qw($VERSION @ISA);
$VERSION = do {my($r)=(q$Revision: 2155 $=~/(\d+)/);sprintf"1.%04d",$r};
@ISA = qw(POE::Filter);

sub BUFFER        () { 0 }
sub TYPE          () { 1 }
sub FINISH        () { 2 }
sub HEADER        () { 3 }
sub CLIENT_PROTO  () { 4 }

use Carp qw(croak);
use HTTP::Status qw( status_message RC_BAD_REQUEST RC_OK RC_LENGTH_REQUIRED );
use HTTP::Request ();
use HTTP::Response ();
use HTTP::Date qw(time2str);
use URI ();

my $HTTP_1_0 = _http_version("HTTP/1.0");
my $HTTP_1_1 = _http_version("HTTP/1.1");

#------------------------------------------------------------------------------

sub new {
  my $type = shift;
  my $self = [
    '',     # BUFFER
    0,      # TYPE
    0,      # FINISH
    undef,  # HEADER
    undef,  # CLIENT_PROTO
  ];
  bless $self, $type;
  $self;
}

#------------------------------------------------------------------------------

sub get_one_start {
    my ($self, $stream) = @_;
    return if ( $self->[FINISH] );
    $stream = [ $stream ] unless ( ref( $stream ) );
    $self->[BUFFER] .= join( '', @$stream );
}

sub get_one {
    my ($self) = @_;
    return ( $self->[FINISH] ) ? [] : $self->get( [] );
}

sub get {
  my ($self, $stream) = @_;

  # Need to check lengths in octets, not characters.
  use bytes;

  # Why?
  local($_);

  # Sanity check.  "finish" is set when a request has completely
  # arrived.  Subsequent get() calls on the same request should not
  # happen.  -><- Maybe this should return [] instead of dying?

  if ($self->[FINISH]) {

    # This works around a request length vs. actual content length
    # error.  Looks like some browsers (mozilla!) sometimes add on an
    # extra newline?

    # return [] unless @$stream and grep /\S/, @$stream;

    my @dump;
    my $offset = 0;
    $stream = $self->[BUFFER].join("", @$stream);
    while (length $stream) {
      my $line = substr($stream, 0, 16);
      substr($stream, 0, 16) = '';

      my $hexdump  = unpack 'H*', $line;
      $hexdump =~ s/(..)/$1 /g;

      $line =~ tr[ -~][.]c;
      push @dump, sprintf( "%04x %-47.47s - %s\n", $offset, $hexdump, $line );
      $offset += 16;
    }

    return [
      $self->_build_error(
        RC_BAD_REQUEST,
        "Did not want any more data.  Got this:" .
        "<p><pre>" . join("", @dump) . "</pre></p>"
      )
    ];
  }

  # Accumulate data in a framing buffer.

  $self->[BUFFER] .= join('', @$stream);

  # If headers were already received, then the framing buffer is
  # purely content.  Return nothing until content-length bytes are in
  # the buffer, then return the entire request.

  if ($self->[HEADER]) {
    my $buf = $self->[BUFFER];
    my $r   = $self->[HEADER];
    my $cl  = $r->content_length() || length($buf) || 0;

    # Some browsers (like MSIE 5.01) send extra CRLFs after the
    # content.  Shame on them.  Now we need a special case to drop
    # their extra crap.
    #
    # We use the first $cl octets of the buffer as the request
    # content.  It's then stripped away.  Leading whitespace in
    # whatever is left is also stripped away.  Any nonspace data left
    # over will throw an error.
    #
    # Four-argument substr() would be ideal here, but it's a
    # relatively recent development.
    #
    # PG- CGI.pm only reads Content-Length: bytes from STDIN.
    if (length($buf) >= $cl) {
      $r->content(substr($buf, 0, $cl));
      $self->[BUFFER] = substr($buf, $cl);
      $self->[BUFFER] =~ s/^\s+//;

      # We are sending this back, so won't need it anymore.
      $self->[HEADER] = undef;
      $self->[FINISH]++;
      return [$r];
    }

    #print "$cl wanted, got " . length($buf) . "\n";
    return [];
  }

  # Headers aren't already received.  Short-circuit header parsing:
  # don't return anything until we've received a blank line.

  return [] unless(
    $self->[BUFFER] =~ /(\x0D\x0A?\x0D\x0A?|\x0A\x0D?\x0A\x0D?)/s
  );

  # Copy the buffer for header parsing, and remove the header block
  # from the content buffer.

  my $buf = $self->[BUFFER];
  $self->[BUFFER] =~ s/.*?(\x0D\x0A?\x0D\x0A?|\x0A\x0D?\x0A\x0D?)//s;

  # Parse the request line.
  if ($buf !~ s/^(\w+)[ \t]+(\S+)(?:[ \t]+(HTTP\/\d+\.\d+))?[^\012]*\012//) {
    return [
      $self->_build_error(RC_BAD_REQUEST, "Request line parse failure.")
    ];
  }
  my $proto = $3 || "HTTP/0.9";

  # Use the request line to create a request object.

  my $r = HTTP::Request->new($1, URI->new($2));
  $r->protocol($proto);
  $self->[CLIENT_PROTO] = $proto = _http_version($proto);

  # Add the raw request's headers to the request object we'll be
  # returning.

  if ($proto >= $HTTP_1_0) {
    my ($key,$val);
    HEADER: while ($buf =~ s/^([^\012]*)\012//) {
      $_ = $1;
      s/\015$//;
      if (/^([\w\-~]+)\s*:\s*(.*)/) {
        $r->push_header($key, $val) if $key;
        ($key, $val) = ($1, $2);
      }
      elsif (/^\s+(.*)/) {
        $val .= " $1";
      }
      else {
        last HEADER;
      }
    }
    $r->push_header($key,$val) if($key);
  }

  $self->[HEADER] = $r;

  # If this is a GET or HEAD request, we won't be expecting a message
  # body.  Finish up.

  my $method = $r->method();
  if ($method eq 'GET' or $method eq 'HEAD') {
    $self->[FINISH]++;
    # We are sending this back, so won't need it anymore.
    $self->[HEADER] = undef;
    return [$r];
  }

  # However, if it's any other type of request, check whether the
  # entire content has already been received!  If so, add that to the
  # request and we're done.  Otherwise we'll expect a subsequent get()
  # call to finish things up.

  #print "post:$buf:\END BUFFER\n";
  #print length($buf)."-".$r->content_length()."\n";

  my $cl = $r->content_length();
  unless(defined $cl) {
    if($self->[CLIENT_PROTO] == 9) {
      return [
        $self->_build_error(
          RC_BAD_REQUEST,
          "POST request detected in an HTTP 0.9 transaction. " .
          "POST is not a valid HTTP 0.9 transaction type. " .
          "Please verify your HTTP version and transaction content."
        )
      ];
    }
    elsif ($method eq 'OPTIONS') {
      $self->[FINISH]++;
      # OPTIONS requests can have an optional content length
      # See http://www.faqs.org/rfcs/rfc2616.html, section 9.2
      $self->[HEADER] = undef;
      return [$r];
    }
    else {
      return [
        $self->_build_error(RC_LENGTH_REQUIRED, "No content length found.")
      ];
    }
  }

  unless ($cl =~ /^\d+$/) {
    return [
      $self->_build_error(
        RC_BAD_REQUEST,
        "Content length contains non-digits."
      )
    ];
  }

  if (length($buf) >= $cl) {
    $r->content(substr($buf, 0, $cl));
    $self->[BUFFER] = substr($buf, $cl);
    $self->[BUFFER] =~ s/^\s+//;
    $self->[FINISH]++;
    # We are sending this back, so won't need it anymore.
    $self->[HEADER] = undef;
    return [$r];
  }

  return [];
}

#------------------------------------------------------------------------------

sub put {
  my ($self, $responses) = @_;
  my @raw;

  # HTTP::Response's as_string method returns the header lines
  # terminated by "\n", which does not do the right thing if we want
  # to send it to a client.  Here I've stolen HTTP::Response's
  # as_string's code and altered it to use network newlines so picky
  # browsers like lynx get what they expect.

  foreach (@$responses) {
    my $code           = $_->code;
    my $status_message = status_message($code) || "Unknown Error";
    my $message        = $_->message  || "";
    my $proto          = $_->protocol || 'HTTP/1.0';

    my $status_line = "$proto $code";
    $status_line   .= " ($status_message)"  if $status_message ne $message;
    $status_line   .= " $message" if length($message);

    # Use network newlines, and be sure not to mangle newlines in the
    # response's content.

    my @headers;
    push @headers, $status_line;
    push @headers, $_->headers_as_string("\x0D\x0A");

    push @raw, join("\x0D\x0A", @headers, "") . $_->content;
  }

  # Allow next request after we're done sending the response.
  $self->[FINISH]--;

  \@raw;
}

#------------------------------------------------------------------------------

sub get_pending {
  my $self = shift;
  croak ref($self)." does not support the get_pending() method\n";
  return;
}

#------------------------------------------------------------------------------
# Functions specific to HTTPD;
#------------------------------------------------------------------------------

# Internal function to parse an HTTP status line and return the HTTP
# protocol version.

sub _http_version {
  local($_) = shift;
  return 0 unless m,^(?:HTTP/)?(\d+)\.(\d+)$,i;
  $1 * 1000 + $2;
}

# Build a basic response, given a status, a content type, and some
# content.

sub _build_basic_response {
  my ($self, $content, $content_type, $status) = @_;

  # Need to check lengths in octets, not characters.
  use bytes;

  $content_type ||= 'text/html';
  $status       ||= RC_OK;

  my $response = HTTP::Response->new($status);

  $response->push_header( 'Content-Type', $content_type );
  $response->push_header( 'Content-Length', length($content) );
  $response->content($content);

  return $response;
}

sub _build_error {
  my($self, $status, $details) = @_;

  $status  ||= RC_BAD_REQUEST;
  $details ||= '';
  my $message = status_message($status) || "Unknown Error";

  return $self->_build_basic_response(
    ( "<html>" .
      "<head>" .
      "<title>Error $status: $message</title>" .
      "</head>" .
      "<body>" .
      "<h1>Error $status: $message</h1>" .
      "<p>$details</p>" .
      "</body>" .
      "</html>"
    ),
    "text/html",
    $status
  );
}

###############################################################################
1;

__END__

=head1 NAME

POE::Filter::HTTPD - convert stream to HTTP::Request; HTTP::Response to stream

=head1 SYNOPSIS

  $httpd = POE::Filter::HTTPD->new();
  $arrayref_with_http_response_as_string =
    $httpd->put($full_http_response_object);
  $arrayref_with_http_request_object =
    $line->get($arrayref_of_raw_data_chunks_from_driver);

=head1 DESCRIPTION

The HTTPD filter parses the first HTTP 1.0 request from an incoming
stream into an HTTP::Request object (if the request is good) or an
HTTP::Response object (if the request was malformed).  To send a
response, give its put() method a HTTP::Response object.

Here is a sample input handler:

  sub got_request {
    my ($heap, $request) = @_[HEAP, ARG0];

    # The Filter::HTTPD generated a response instead of a request.
    # There must have been some kind of error.  You could also check
    # (ref($request) eq 'HTTP::Response').
    if ($request->isa('HTTP::Response')) {
      $heap->{wheel}->put($request);
      return;
    }

    # Process the request here.
    my $response = HTTP::Response->new(200);
    $response->push_header( 'Content-Type', 'text/html' );
    $response->content( $request->as_string() );

    $heap->{wheel}->put($response);
  }

Please see the documentation for HTTP::Request and HTTP::Response.

=head1 PUBLIC FILTER METHODS

Please see POE::Filter.

=head1 CAVEATS

It is possible to generate invalid HTTP using libwww. This is specifically a
problem if you are talking to a Filter::HTTPD driven daemon using libwww. For
example, the following code (taken almost verbatim from the
HTTP::Request::Common documentation) will cause an error in a Filter::HTTPD
daemon:

  use HTTP::Request::Common;
  use LWP::UserAgent;

  my $ua = LWP::UserAgent->new();
  $ua->request(POST 'http://some/poe/driven/site', [ foo => 'bar' ]);

By default, HTTP::Request is HTTP version agnostic. It makes no attempt to add
an HTTP version header unless you specifically declare a protocol using
C<< $request->protocol('HTTP/1.0') >>.

According to the HTTP 1.0 RFC (1945), when faced with no HTTP version header,
the parser is to default to HTTP/0.9. Filter::HTTPD follows this convention. In
the transaction detailed above, the Filter::HTTPD based daemon will return a 400
error since POST is not a valid HTTP/0.9 request type.

=head1 Streaming Media

It is perfectly possible to use Filter::HTTPD for streaming output
media.  Even if it's not possible to change the input filter from
Filter::HTTPD, by setting the output_filter to Filter::Stream and
omitting any content in the HTTP::Response object.

  $wheel->put($response); # Without content, it sends just headers.
  $wheel->set_output_filter(POE::Filter::Stream->new());
  $wheel->put("Raw content.");

=head1 SEE ALSO

POE::Filter.

The SEE ALSO section in L<POE> contains a table of contents covering
the entire POE distribution.

=head1 BUGS

=over 4

=item * Keep-alive is not supported.

=item * The full http 1.0 spec is not supported, specifically DELETE, LINK, and UNLINK.

=back

=head1 AUTHORS & COPYRIGHTS

The HTTPD filter was contributed by Artur Bergman.

Please see L<POE> for more information about authors and contributors.

=cut
