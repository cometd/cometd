package POE::Filter::Atom;

use strict;
use warnings;

use base qw( POE::Filter );

use Carp qw( croak );
use XML::Atom::Feed;
use XML::Atom::Entry;

our $VERSION = '0.01';

sub BUFFER () { 0 }

sub new {
    my $class = shift;
    croak "$class requires an even number of parameters" if @_ % 2;

    bless( [
        [],          # BUFFER
    ], ref $class || $class );
}

sub _get {
    my ($self, $lines) = @_;
    my $ret = [];

#    foreach my $json (@$lines) {
#        if ( my $obj = eval { $self->[ OBJ ]->jsonToObj( $json ) } ) {
#            push( @$ret, $obj );
#        } else {
#            warn "Couldn't convert json to an object: $@\n";
#        }
#    }

    return $ret;
}

sub get_one_start {
    my ($self, $lines) = @_;
    $lines = [ $lines ] unless ( ref( $lines ) );
    push( @{ $self->[ BUFFER ] }, @{ $lines } );
}

sub get_one {
    my $self = shift;
    my $ret = [];

    return $ret unless ( $self->[ BUFFER ]->[ -1 ] );

    # TODO don't skip, but return the time instead of a feed?
    if ( $self->[ BUFFER ]->[ -1 ] =~ m~^<time>~io ) {
        # XXX is delete or splice faster?
        shift @{$self->[ BUFFER ]};
        return $ret;
    }
    
    # TODO pass this back too?
    if ( $self->[ BUFFER ]->[ -1 ] =~ m~^<sorryTooSlow~io ) {
        shift @{$self->[ BUFFER ]};
        return $ret;
    }

    return $ret unless ( $self->[ BUFFER ]->[ -1 ] =~ m~</feed>~io );

    my $data = join ( "\n", @{ $self->[ BUFFER ] } );
    $self->[ BUFFER ] = [];

    eval {
        if ( my $feed = XML::Atom::Feed->new( \$data ) ) {
            push( @$ret, $feed );
        }
    };
    warn "$@  : $data" if ($@);

    return $ret;
}

sub put {
    my ($self, $objects) = @_;
    my $ret = [];

    foreach my $obj (@$objects) {
        push( @$ret, $obj->as_xml );
    }
    
    return $ret;
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

    my $filter = POE::Filter::Stackable->new();
    $filter->push(
        POE::Filter::Line->new(),
        POE::Filter::Atom->new(),
    );

=head1 DESCRIPTION

POE::Filter::Atom provides a POE filter for parsing an XML::Atom stream. It is
suitable for use with L<POE::Filter::Stackable>.  Preferably with L<POE::Filter::Line>.

=head1 METHODS

=over

=item *

new

Creates a new POE::Filter::Atom object.

=item *

get

Takes an arrayref which is contains XML Atom lines. Returns an arrayref of objects.

=item *

put

Takes an arrayref containing objects, returns an arrayref of XML Atom lines.

=back

=head1 AUTHOR

David Davis <xantus@cpan.org>

=head1 LICENSE

Artistic

=head1 SEE ALSO

L<POE>
L<XML::Atom>
L<POE::Filter::Stackable>
L<POE::Filter::Line>

=cut


1;
