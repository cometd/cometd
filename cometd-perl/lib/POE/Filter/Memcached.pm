package POE::Filter::Memcached;
# Copyright 2007 (c) David Davis - All Rights Reserved
# Artistic License / BSD

use strict;
use warnings;

use bytes;

use POE::Filter;

use vars qw($VERSION @ISA);
$VERSION = '0.01';
@ISA = qw(POE::Filter);
use Carp qw(carp croak);

sub DEBUG () { 0 }
sub FRAMING_BUFFER   () { 0 }
sub INPUT_REGEXP     () { 1 }
sub OUTPUT_LITERAL   () { 2 }
sub STATE            () { 3 }
sub DATA             () { 4 }

sub STATE_LINE       () { 0 }
sub STATE_GET        () { 1 }

sub new {
  my $type = shift;

  croak "$type requires an even number of parameters" if @_ and @_ & 1;
  my %params = @_;

  carp("$type ignores unknown parameters: ", join(', ', sort keys %params))
    if scalar keys %params;

  my $self = bless [
    '',             # FRAMING_BUFFER
    "\\x0D\\x0A",   # INPUT_REGEXP
    "\x0D\x0A",     # OUTPUT_LITERAL
    STATE_LINE,     # STATE 
    undef,          # DATA
  ], $type;

  $self;
}

sub get_one_start {
  my ($self, $stream) = @_;

  DEBUG and do {
    my $temp = join '', @$stream;
    $temp = unpack 'H*', $temp;
    warn "got some raw data: $temp\n";
  };

  $self->[FRAMING_BUFFER] .= join '', @$stream;
}

sub get_one {
    my $self = shift;
    my @out;
    
    STATE: while (1) {
    
        if ( $self->[STATE] == STATE_LINE  ) {
            # Process as many newlines an we can find.
            while (1) {
                DEBUG and warn unpack 'H*', $self->[INPUT_REGEXP];
                
                last STATE
                    unless $self->[FRAMING_BUFFER] =~ s/^(.*?)$self->[INPUT_REGEXP]//s;
                
                my $data = $1;
                DEBUG and warn "got line: <<", unpack('H*', $data), ">>\n";
                
                # get foo bar
                # VALUE foo <flags> <bytes>\r\n
                # <data block>\r\n
                # VALUE bar <flags> <bytes>\r\n
                # <data block>\r\n
                # END\r\n
                if ( $data =~ m/^VALUE (\S+) (\d+) (\d+)/ ) {
                    $self->[STATE] = STATE_GET;
                    push(@{$self->[DATA]}, {
                        key => $1,
                        flags => $2,
                        len => $3,
                    });
                    next STATE;
                } elsif ( $data =~ m/^STAT (\S+) (\S+)/ ) {
                    $self->[STATE] = STATE_LINE;
                    # STAT <name> <value>\r\n
                    if ( $self->[DATA] ) {
                        $self->[DATA]->{$1} = $2;
                    } else {
                        $self->[DATA] = { $1 => $2 };
                    }
                    next STATE;
                } elsif ( $data eq 'END' ) {
                    if ( ref( $self->[DATA] ) eq 'ARRAY' || !defined( $self->[DATA] ) ) {
                        push(@out, { data => $self->[DATA], cmd => 'get' });
                    } else {
                        push(@out, { data => $self->[DATA], cmd => 'stats' });
                    }
                    delete $self->[DATA];
                } elsif ( $data =~ m/^(OK|NOT_STORED|STORED|DELETED|NOT_FOUND|ERROR)/ ) {
                    push(@out, { status => $1 });
                } elsif ( $data =~ m/^((:?CLIENT|SERVER)_ERROR) (.*)/ ) {
                    push(@out, { status => $1, error => $2 });
                } elsif ( $data =~ m/^VERSION (.*)/ ) {
                    push(@out, { version => $1 });
                } else {
                    push(@out, { result => $data });
                }
        
                return \@out;
            }
        } else {
            if ( $self->[DATA] ) {
                last STATE
                    unless ( length( $self->[FRAMING_BUFFER] ) >= $self->[DATA]->[-1]->{len}+2 );
                # get the object
                $self->[DATA]->[-1]->{obj} = substr( $self->[FRAMING_BUFFER], 0, $self->[DATA]->[-1]->{len}, '' );
                substr( $self->[FRAMING_BUFFER], 0, 2, '' ); # \r\n
                $self->[STATE] = STATE_LINE;
            }
            # switch states, so we can catch the end
            $self->[STATE] = STATE_LINE;
        }
    }

    return [ ];
}

sub put {
    my ($self, $lines) = @_;

    my @raw;
    foreach (@$lines) {
        my $cmd = lc($_->{cmd});
        if ( $cmd eq 'get' ) {
            # TODO check key
            $_->{keys} = [ $_->{key} ]
                if ( $_->{key} );
            push( @raw,
                "$cmd ".join(' ',@{$_->{keys}}).$self->[OUTPUT_LITERAL]
            );
        } elsif ( $cmd eq 'delete' ) {
            my $time = exists( $_->{time} ) ? $_->{time} : 0;
            push( @raw,
                "$cmd $_->{key} $time".$self->[OUTPUT_LITERAL]
            );
        } elsif ( $cmd eq 'incr' || $cmd eq 'decr' ) {
            my $val = exists( $_->{by} ) ? $_->{by} : 1;
            push( @raw,
                "$cmd $_->{key} $val".$self->[OUTPUT_LITERAL]
            );
        } elsif ( $cmd eq 'version' || $cmd eq 'quit' || $cmd eq 'flush_all' ) {
            push( @raw, $cmd.$self->[OUTPUT_LITERAL] );
        } elsif ( $cmd eq 'stats' ) {
            # stats\r\n
            # stats <args>\r\n (not documented)
            push( @raw, $cmd.( exists( $_->{args} ) ? ' '.$_->{args} : '' ).$self->[OUTPUT_LITERAL] );
        } else {
            my $flags = exists( $_->{flags} ) ? $_->{flags} : 0;
            my $exptime = exists( $_->{exp} ) ? $_->{exp} : 0;
            my $len = length( $_->{obj} );
            if ( $cmd =~ m/^(set|add|replace)$/ ) {
                # set <key> <flags> <exptime> <bytes>\r\n
                push( @raw,
                    "$cmd $_->{key} $flags $exptime $len".$self->[OUTPUT_LITERAL].
                    "$_->{obj}".$self->[OUTPUT_LITERAL]
                );
                warn "sending: $raw[-1]";
            } else {
                warn "invalid command used in memcached filter: $cmd";
            }
        }
    }

    \@raw;
}

sub get_pending {
  my $self = shift;
  return [ $self->[FRAMING_BUFFER] ] if length $self->[FRAMING_BUFFER];
  return undef;
}

1;

__END__

=head1 NAME

POE::Filter::Memcached - A memcached filter

=head1 SYNOPSIS

  $filter = POE::Filter::Memcached->new();
  $arrayref_of_data =
    $filter->get($arrayref_of_raw_chunks_from_driver);
  $arrayref_of_streamable_chunks_for_driver =
    $filter->put($arrayref_of_data);
  $arrayref_of_leftovers =
    $filter->get_pending();

=head1 DESCRIPTION

The Memcached filter translates the memcached client protocol into
perl data structures that are easier to manipulate.

See the protocol docs:
L<http://code.sixapart.com/svn/memcached/trunk/server/doc/protocol.txt>

=head1 PUBLIC FILTER METHODS

Please see POE::Filter.

=head1 SEE ALSO

POE::Filter.

The SEE ALSO section in L<POE> contains a table of contents covering
the entire POE distribution.

=head1 AUTHOR & COPYRIGHT

Copyright 2007 (c) David Davis, All Rights Reserved

=cut
