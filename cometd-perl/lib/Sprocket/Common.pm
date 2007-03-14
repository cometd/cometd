package Sprocket::Common;

our %hex_chr;

BEGIN {
    for ( 0 .. 255 ) {
        my $h = sprintf( "%%%02X", $_ );
        $hex_chr{lc($h)} = $hex_chr{uc($h)} = chr($_);
    }
}


sub import {
    my $class = shift;
    my $package = caller();

    my @exports = qw(
        uri_unescape
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

sub uri_unescape {
    my $es = shift or return;
    $es =~ tr/+/ /; # foo=this+is+a+test
    $es =~ s/(%[0-9a-fA-F]{2})/$hex_chr{$1}/gs;
    return $es;
}

# ThisIsCamelCase -> this_is_camel_case
# my %opts = &adjust_params;
# my $t = adjust_params($f); # $f being a hashref
sub adjust_params {
    my $o = ( $#_ == 0 && ref( $_[ 0 ] ) ) ? shift : { @_ };
    foreach my $k ( keys %$o ) {
        local $_ = "$k";
        s/([A-Z][a-z]+)/lc($1)."_"/ge; s/_$//;
        $o->{$_} = delete $o->{$k};
    }
    return wantarray ? %$o : $o;
}


1;
