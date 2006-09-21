package POE::Filter::JSON;

use Carp;
use JSON;

use strict;
use warnings;

use base qw( POE::Filter );

our $VERSION = '0.02';

sub OBJ    () { 0 }
sub BUFFER () { 1 }
sub PARAMS () { 2 }

sub new {
    my $type = shift;
    croak "$type requires an even number of parameters" if @_ % 2;

    bless [
        JSON->new(), # OBJ
        [],          # BUFFER
        { @_ },      # PARAMS
    ], $type;
}

sub get {
    my ($self, $lines) = @_;
    my $ret = [];

    foreach my $json (@$lines) {
        warn "json:$json\n";
        if ( my $obj = eval { $self->[ OBJ ]->jsonToObj( $json ) } ) {
            push( @$ret, $obj );
        } else {
            warn "Couldn't convert json to an object: $@\n";
        }
    }
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

    if ( my $line = shift ( @{ $self->[ BUFFER ] } ) ) {
        warn "line:$line\n";
        if ( my $json = eval { $self->[ OBJ ]->jsonToObj( $line ) } ) {
            push( @$ret, $json );
        } else {
            warn "Couldn't convert json to object: $@\n";
        }
    }

    return $ret;
}

sub put {
    my ($self, $objects) = @_;
    my $ret = [];

    foreach my $obj (@$objects) {
        if ( my $json = $self->[ OBJ ]->objToJson( $obj, $self->[ PARAMS ] ) ) {
            push( @$ret, $json );
        } else {
            warn "Couldn't convert object to json\n";
        }
    }
    
    return $ret;
}

1;

__END__

=head1 NAME

POE::Filter::JSON - A POE filter using JSON

=head1 SYNOPSIS

    use POE::Filter::JSON;

    my $filter = POE::Filter::JSON->new();
    my $obj = { foo => 1, bar => 2 };
    my $json_array = $filter->put( [ $obj ] );
    my $obj_array = $filter->get( $json_array );

    use POE qw( Filter::Stackable Filter::Line Filter::JSON );

    my $filter = POE::Filter::Stackable->new();
    $filter->push(
        POE::Filter::JSON->new( delimiter => 0 ),
        POE::Filter::Line->new(),
    );

=head1 DESCRIPTION

POE::Filter::JSON provides a POE filter for performing object conversion using L<JSON>. It is
suitable for use with L<POE::Filter::Stackable>.  Preferably with L<POE::Filter::Line>.

=head1 METHODS

=over

=item *

new

Creates a new POE::Filter::JSON object. It takes arguments that are passed to objToJson() (as the 2nd argument). See L<JSON> for details.

=item *

get

Takes an arrayref which is contains json lines. Returns an arrayref of objects.

=item *

put

Takes an arrayref containing objects, returns an arrayref of json linee.

=back

=head1 AUTHOR

David Davis <xantus@cpan.org>

=head1 LICENSE

New BSD

=head1 SEE ALSO

L<POE>
L<JSON>
L<POE::Filter::Stackable>
L<POE::Filter::Line>

=cut

