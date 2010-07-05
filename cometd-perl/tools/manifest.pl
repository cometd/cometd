#!/usr/bin/perl

use strict;
use warnings;

my $base = shift || $ENV{PWD};
my @dirs = "/";
my @ignore = (
    qr~html/~,
    qr~perlbal-lib/~,
    qr~lab/~,
);
my @list;

while( my $dir = shift @dirs ) {
    $dir .= "/" unless $dir =~ m!/$!;
    opendir( DIR, "$base$dir" );
    my @files = grep { !/^\./ } readdir( DIR );
    closedir( DIR );
    DIR: foreach my $file ( @files ) {
        foreach my $r ( @ignore ) {
            next DIR if ( "$base$dir$file" =~ /$r/ );
        }
        if ( -d "$base$dir$file" ) {
            push(@dirs, "$dir$file" );
        } elsif ( -e "$base$dir$file" ) {
            push(@list, "$dir$file" );
        }
    }
}

foreach ( sort { $a cmp $b } @list ) {
    s!^/!!;
    print "$_\n";
}
