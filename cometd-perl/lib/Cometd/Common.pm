package Cometd::Common;

our %hex_chr;

BEGIN {
    for ( 0 .. 255 ) {
        my $h = sprintf( "%02x", $_ );
        $hex_chr{lc($h)} = $hex_chr{uc($h)} = chr($_);
    }
}


sub import {
    my $class = shift;
    my $package = caller();

    my @exports = qw(
        unescape
        adjust_params
    );

    push( @exports, @_ ) if ( @_ );
    
    {
        no strict 'refs';
        foreach my $sub ( @exports ) {
            *{ $package . '::' . $sub } = \&$sub;
        }
    }
}

sub unescape {
    my $es = shift;
    $es =~ tr/+/ /;
    $es =~ s/(%[0-9a-fA-F]{2})/$hex_chr{$1}/gs;
    return $es;
}

# ThisIsCamelCase -> this_is_camel_case
# my %opts = &adjust_params;
# my $t = adjust_params($f); # $f being a hashref
sub adjust_params {
    my $o;
    if ( $#_ == 0 && ref( $_[ 0 ] ) ) {
        $o = shift;
    } else {
        %$o = @_;
    }
    foreach my $k ( keys %$o ) {
        local $_ = "$k";
        s/([A-Z][a-z]+)/lc($1)."_"/ge; s/_$//;
        $o->{$_} = delete $o->{$k};
    }
    return wantarray ? %$o : $o;
}


1;
