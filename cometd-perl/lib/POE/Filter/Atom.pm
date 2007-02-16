package POE::Filter::Atom;

use strict;
use warnings;

use base qw( POE::Filter );

use Carp qw( croak );
use XML::Atom::Feed;
use XML::Atom::Entry;

our $VERSION = '0.01';

sub BUFFER () { 0 }
sub CDATA  () { 1 }

sub new {
    my $class = shift;
    croak "$class requires an even number of parameters" if @_ % 2;

    bless( [
        [],          # BUFFER
        0,           # CDATA
    ], ref $class || $class );
}

sub get_one_start {
    my ($self, $lines) = @_;
    $lines = [ $lines ] unless ( ref( $lines ) );
    push( @{ $self->[ BUFFER ] }, @{ $lines } );
}

# sub get ?

sub get_one {
    my $self = shift;
    my $ret = [];

    my $l = $self->[ BUFFER ]->[ -1 ];
    
    return $ret unless ( $l );

    # XXX don't skip, but return the time instead of a feed?
    if ( $l =~ m~^<time>~io ) {
        # XXX is splice faster?
        shift @{$self->[ BUFFER ]};
        return $ret;
    }
    
    # XXX pass this back too?
    if ( $l =~ m~^<sorryTooSlow~io ) {
        shift @{$self->[ BUFFER ]};
        return $ret;
    }

    # CDATA
    $self->[ CDATA ] = 1 if ( $l =~ m/<!\[CDATA\[/ );
    
    # the CDATA block could end on the same line 
    $self->[ CDATA ] = 0 if ( $self->[ CDATA ] && $l =~ m/\]\]>/ );

    return $ret if ( $self->[ CDATA ] );

    return $ret unless ( $l =~ m~</feed>~io );

    my $data = join( "\n", @{ $self->[ BUFFER ] } );
    $self->[ BUFFER ] = [];
    
    open(FH,">>debug.txt");
    print FH "$data\n";
    print FH "--------\n";
    close(FH);

    eval {
        if ( my $feed = XML::Atom::Feed->new( \$data ) ) {
            push( @$ret, $feed );
        }
    };
    warn "$@ when parsing atom stream: $data" if ( $@ );

    return $ret;
}

sub put {
    my ($self, $objects) = @_;

    return [ map { $_->as_xml } @$objects ];
}

1;

__END__

=head1 NAME

POE::Filter::Atom - A POE filter using XML::Atom

=head1 SYNOPSIS

    use POE::Filter::Atom;

    my $filter = POE::Filter::Atom->new();
    my $feed_array = $filter->put( [ $xml_atom_obj ] );
    my $obj_array = $filter->get( $feed_array );

    use POE qw( Filter::Stackable Filter::Line Filter::Atom );

    my $filter = POE::Filter::Stackable->new(
        Filters => [
            POE::Filter::Line->new(),
            POE::Filter::Atom->new(),
        ]
    );

=head1 DESCRIPTION

POE::Filter::Atom provides a POE filter for parsing an XML::Atom stream. It
should be used with L<POE::Filter::Stackable> and L<POE::Filter::Line>.

=head1 METHODS

=over

=item *

new

Creates a new POE::Filter::Atom object.

=item *

get

Takes an arrayref which is contains XML Atom lines from L<POE::Filter::line>.
Returns an arrayref of objects.

=item *

put

Takes an arrayref containing objects, returns an arrayref of XML Atom lines.

=back

=head1 AUTHOR

David Davis <xantus@cpan.org>

=head1 LICENSE

Artistic License (AL)

=head1 SEE ALSO

L<POE>
L<XML::Atom>
L<POE::Filter::Stackable>
L<POE::Filter::Line>

=cut


1;
