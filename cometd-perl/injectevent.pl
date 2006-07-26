#!/usr/bin/perl

use LWP::UserAgent;

my $ua = LWP::UserAgent->new();


$ua->agent('COMETd Event Injector/1.0');

$ua->default_header( "X-REPROXY-SERVICE" => "cometd", "X-COMETd" => "ID=*; action=post;" );

#my $r = $ua->get("http://127.0.0.1:2022/?channel=meta%2Fglobal&data=".$ARGV[0]);

my $r = $ua->post("http://10.0.0.11:2022/", {
    channel => 'meta/global',
    data => $ARGV[ 0 ],
});

if ($r) {
    print "Success\n".$r->content;
} else {
    print "Fail\n";
}

exit 0;
