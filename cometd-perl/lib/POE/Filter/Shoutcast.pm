package POE::Filter::Shoutcast;

use strict;
use warnings;

use base qw( POE::Filter );
use bytes;

use Carp qw( croak );

our $VERSION = '0.01';

sub BUFFER        () { 0 }
sub OUT_METAINT   () { 1 }
sub OUT_BYTES     () { 2 }
sub IN_METAINT    () { 3 }
sub IN_BYTES      () { 4 }
sub BUFFIN_BYTES  () { 5 }
sub BLOCKSIZE     () { 6 }
sub GET_METALEN   () { 7 }
sub OFFSET        () { 8 }
sub OUT_OFFSET    () { 9 }
sub MODE          () { 10 }


sub new {
    my $class = shift;
    croak "$class requires an even number of parameters" if @_ % 2;

    my %ops = @_;
    my $self = bless( [
        '',          # BUFFER
        undef,       # OUT_METAINT
        0,           # OUT_BYTES
        undef,       # IN_METAINT
        0,           # IN_BYTES
        0,           # BUFFIN_BYTES
        2048,        # BLOCKSIZE
        0,           # GET_METALEN
        0,           # OFFSET
        0,           # OUT_OFFSET
        0,           # MODE
    ], ref $class || $class );
    
    if ( $ops{metaint} ) {
        $self->[ OUT_METAINT ] = $self->[ IN_METAINT ] = $ops{metaint};
    }

    if ( $ops{in_metaint} ) {
        $self->[ IN_METAINT ] = $ops{in_metaint};
    }
    
    if ( $ops{out_metaint} ) {
        $self->[ OUT_METAINT ] = $ops{out_metaint};
    }
    
    if ( $ops{blocksize} ) {
        $self->[ BLOCKSIZE ] = $ops{blocksize};
    }

    return $self;
}

sub set_metaint {
    my $self = shift;
    my $metaint = shift;
    if ( $metaint == -1 ) {
        $self->[ OUT_METAINT ] = $self->[ IN_METAINT ] = 0;
        $self->[ MODE ] = 2;
        return;
    }
    $self->[ OUT_METAINT ] = $self->[ IN_METAINT ] = $metaint;
}

sub get_one_start {
    my ($self, $blocks) = @_;
    $blocks = [ $blocks ] unless ( ref( $blocks ) );
    my $out = join( '', @$blocks );
    my $len = length( $out );
    $self->[ BUFFIN_BYTES ] += $len;
    $self->[ IN_BYTES ] += $len;
    $self->[ BUFFER ] .= $out;
    my @dump;
    while (length $out) {
      my $line = substr($out, 0, 16, '');

      my $hexdump  = unpack 'H*', $line;
      $hexdump =~ s/(..)/$1 /g;

      $line =~ tr[ -~][.]c;
      push(@dump, sprintf( "%04x %-47.47s - %s\n", $self->[ OFFSET ], $hexdump, $line ) );
      $self->[ OFFSET ] += 16;
    }
    open(FH,">>sc.txt");
    print FH join('', @dump)."inbytes: ".$self->[ IN_BYTES ]."\n";
    close(FH);
    return;
}

# sub get ?

# XXX temporary
sub get_one {
    my $self = shift;

    my $ret = $self->_get_one();
    return $ret unless ( @$ret );
#    return $ret if ( $self->[ MODE ] == 0 );

    my $out = join( '', @$ret );
    my $len = length( $out );
    $self->[ OUT_BYTES ] += $len;
    my @dump;
    while (length $out) {
        my $line = substr($out, 0, 16, '');

        my $hexdump  = unpack 'H*', $line;
        $hexdump =~ s/(..)/$1 /g;

        $line =~ tr[ -~][.]c;
        push(@dump, sprintf( "%04x %-47.47s - %s\n", $self->[ OUT_OFFSET ], $hexdump, $line ) );
        $self->[ OUT_OFFSET ] += 16;
    }
    open(FH,">>out_sc.txt");
    print FH join('', @dump)."outbytes: ".$self->[ OUT_BYTES ]."\n";
    close(FH);

    return $ret;
}

sub _get_one {
    my $self = shift;
    my $ret = [];

    if ( $self->[ MODE ] == 0 ) {
        return $ret unless ( $self->[ BUFFER ] =~ m/\x0d\x0a\x0d\x0a/ );

        @$ret = substr( $self->[ BUFFER ], 0, ( index( $self->[ BUFFER ], "\x0d\x0a\x0d\x0a" ) + 4 ), '' );
#        warn "header: @$ret";
#        my $line = substr( $self->[ BUFFER ], 0, 4 ); 
#        my $hexdump  = unpack 'H*', $line;
#        $hexdump =~ s/(..)/$1 /g;
#        $line =~ tr[ -~][.]c;
#        warn "hex of data after header: $hexdump : $line";
        $self->[ MODE ] = 1;
        $self->[ IN_BYTES ] = $self->[ BUFFIN_BYTES ] = length( $self->[ BUFFER ] );
        open(FH,">>sc.txt");
        print FH "reset to ".$self->[ BUFFIN_BYTES ]." header: ".length( $ret->[ 0 ] )."\n";
        close(FH);
        return $ret;
    } elsif ( $self->[ MODE ] == 1 ) {
        return $ret unless ( $self->[ IN_METAINT ] );
    } elsif ( $self->[ MODE ] == 2 ) {
        my $loc = index( $self->[ BUFFER ], "StreamTitle" );
        if( $loc > 0 ) {
            $loc--;
            push( @$ret, substr( $self->[ BUFFER ], 0, $loc, '' ) );
            $self->[ BUFFIN_BYTES ] = length( $self->[ BUFFER ] );
            my $len = ord( substr( $self->[ BUFFER ], 0, 1 ) );
            $self->[ GET_METALEN ] = $len * 16;
            $self->[ IN_METAINT ] = 8192;
            warn "found meta at loc: $loc, length: $len *16= ".$self->[ GET_METALEN ];
            $self->[ MODE ] = 1;
            #return $ret;
        }
    }

    return $ret unless ( $self->[ BUFFIN_BYTES ] >= $self->[ IN_METAINT ] );

    if ( $self->[ IN_METAINT ] ) {

        if ( !$self->[ GET_METALEN ] ) {
            
            my $slen = $self->[ IN_METAINT ];
            if ( $self->[ BUFFIN_BYTES ] >= $slen ) {
#                warn "buff_outbytes: ".$self->[BUFFIN_BYTES]." >= than in_metaint: ".$self->[ IN_METAINT ]." slen: $slen";
                # splice out the data
                #my $line = substr( $self->[ BUFFER ], $slen - 5, 10 );
                #my $hexdump  = unpack 'H*', $line;
                #$hexdump =~ s/(..)/$1 /g;
                #$line =~ tr[ -~][.]c;
                #warn "around $slen : $hexdump : $line";
                push( @$ret, substr( $self->[ BUFFER ], 0, $slen, '' ) );
                my $len = ord( substr( $self->[ BUFFER ], 0, 1 ) ) * 16;
                $self->[ IN_BYTES ] = $self->[ BUFFIN_BYTES ] = length( $self->[ BUFFER ] );
                
                if ( $len > 254 ) { # XXX arbitrary limit
#                    warn "startlen $slen  len of data: $len TOO MUCH, SKIPPING\n";
                    $self->[ GET_METALEN ] = 0;
                    $self->[ MODE ] = 2; # find meta
                } else {
#                    warn "startlen $slen  len of data: $len buffer length left: ".$self->[ BUFFIN_BYTES ]."\n";
                    $self->[ GET_METALEN ] = $len;
                }

                return $ret;
            }
        }

        if ( $self->[ GET_METALEN ] ) {
            if ( $self->[ BUFFIN_BYTES ] >= $self->[ GET_METALEN ] ) {
                my $meta = substr( $self->[ BUFFER ], 1, $self->[ GET_METALEN ], '' );
                if ( $meta !~ m/^.tream.itle/ ) {
                    # @$!!@% shoutcast servers can't follow the damn metaint they advertise
                    $self->[ MODE ] = 2; # find meta
                    $self->[ BUFFER ] = join( '', @$ret ).$meta;
                    $self->[ GET_METALEN ] = 0;
                    $ret = [];
                } else {
                    # remove length prefix
                    substr( $self->[ BUFFER ], 0, 1, '' );
                    push( @$ret, { meta => $meta } );
                }
                $self->[ BUFFIN_BYTES ] = length( $self->[ BUFFER ] );
                $self->[ GET_METALEN ] = 0;
            }
        }

    }

    return $ret;
}

sub put {
    my ($self, $objects) = @_;

    my @out;
    foreach ( @$objects ) {
#        next unless( $_ );
        $self->[ OUT_BYTES ] += length( $_ );
        if ( $self->[ MODE ] == 0 ) {
            push( @out, "$_\r\n" );
#            warn "sending $_";
        } else {
            push( @out, $_ );
        }
    }

    return \@out;
}

1;

__END__

=head1 NAME

POE::Filter::Shoutcast - A POE filter that filters icy meta data in a stream

=head1 SYNOPSIS

    use POE::Filter::Shoutcast;

    my $filter = POE::Filter::Shoutcast->new();
    my $feed_array = $filter->put( [ $stream ] );
    my $obj_array = $filter->get( $stream_array );

=head1 DESCRIPTION

POE::Filter::Shoutcast provides a POE filter for parsing icy meta data in or
out of a stream.

=head1 METHODS

=over

=item *

new

Creates a new POE::Filter::Shoutcast object.  Pass the metaint param or this
filter will simply be the same as Filter::Stream and pass data untouched.

=item *

get

Takes an arrayref which is contains a data stream. Returns an arrayref of
stream data.

=item *

put

Takes an arrayref containing stream data, returns an arrayref of stream data

=back

=head1 AUTHOR

David Davis <xantus@cpan.org>

=head1 LICENSE

Artistic License (AL)

=head1 SEE ALSO

L<Sprocket>
L<POE>

=cut


1;
