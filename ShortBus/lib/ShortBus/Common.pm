package ShortBus::Common;

our %hex_chr;

BEGIN {
    for ( 0 .. 255 ) {
        $hex_chr{lc( sprintf "%02x", $_ )} = chr($_);
    }
}

sub unescape {
	my $es = shift;
	$es =~ s/([0-9a-fA-F]{2})/$hex_chr{$1}/gs;
	return $es;
}

1;
