package Sprocket::Common;

use strict;
use warnings;

our %hex_chr;
our %chr_hex;

BEGIN {
    for ( 0 .. 255 ) {
        my $h = sprintf( "%%%02X", $_ );
        my $c = chr($_);
        $chr_hex{$c} = $h;
        $hex_chr{lc($h)} = $hex_chr{uc($h)} = $c;
    }
}


sub import {
    my $class = shift;
    my $package = caller();

    my @exports = qw(
        uri_unescape
        uri_escape
        adjust_params
    );

    push( @exports, @_ ) if ( @_ );
    
    no strict 'refs';
    foreach my $sub ( @exports ) {
        *{ $package . '::' . $sub } = \&$sub;
    }
}

sub uri_escape {
    my $es = shift or return;
    $es =~ s/([^A-Za-z0-9\-_.!~*'()])/$chr_hex{$1}||_try_utf8($1)/ge;
    return $es;
}

sub _try_utf8 {
    my $c = shift;
    $c = eval { utf8::encode($c); };
    if ( $@ ) {
        warn $@;
        return '';
    }
    return $c
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
