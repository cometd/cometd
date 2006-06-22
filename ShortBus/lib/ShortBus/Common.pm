package ShortBus::Common;

our %hex_chr;

BEGIN {
    for ( 0 .. 255 ) {
        my $h = sprintf( "%02x", $_ );
        $hex_chr{lc($h)} = $hex_chr{uc($h)} = chr($_);
    }
}


sub import {
    my ($class) = @_;
    my $package = caller();

    my %exports = qw(
        unescape   \&unescape
        params     \&params
    );

    {
        no strict 'refs';
        foreach (keys %exports) {
            *{ $package . '::' . $_ } = eval "$exports{$_}";
        }
    }
}

sub unescape {
    my $es = shift;
    $es =~ tr/+/ /;
    $es =~ s/(%[0-9a-fA-F]{2})/$hex_chr{$1}/gs;
    return $es;
}

sub params {
    my Perlbal::ClientProxy $client = shift;
    my $r = $client->{res_headers};
    
    my $o = {};
    
    my $input = '';

    if ($r->{method} ne "GET") {
        # TODO proper client read
        my $data = $client->read(4096);
    
#        $input = join('',@{$client->{read_buf}}).$$data;
        $input = $$data;
        
        if ($r->header('Content-Type')
            && $r->header('Content-Type') =~ /^multipart\/form-data/) {
            
            my ($boundary) = ( $r->header('Content-Type') =~ s/^.*boundary=(.*)$/$1/ );
            my @pairs = split(/--$boundary/, $input);
            @pairs = splice(@pairs,1,$#pairs-1);
            
            foreach (@pairs) {
                s/[\r]\n$//g;
                my ($blankline,$name);
                my ($dump, $first, $data) = split(/[\r]\n/, $_, 3);
                next if $first =~ /filename=\"\"/;
                
                $first =~ s/^Content-Disposition: form-data; //i;
                my (@columns) = split(/;\s+/, $first);
                ($name = $columns[0]) =~ s/^name="([^"]+)"$/$1/g;
                
                if ($#columns > 0) {
                    if ($data =~ /^Content-Type:/i) {
                        ($o->{$name}{'Content-Type'}, $blankline, $data) = split(/[\r]\n/, $data, 3);
                        $o->{$name}{'Content-Type'} =~ s/^Content-Type: ([^\s]+)$/$1/gi;
                    } else {
                        ($blankline, $data) = split(/[\r]\n/, $data, 2);
                        $o->{$name}{'Content-Type'} = "application/octet-stream";
                    }
                } else {
                    ($blankline, $data) = split(/[\r]\n/, $data, 2);
                    
                    if ( grep(/^$name$/, keys(%$o) ) ) {
                        # strange, we never got here
                        # on a multipart form, if you have 2 inputs with the same name
                        # then this turns the hash into an array -David
                        #if (@{$$o{in}{$name}} > 0) {  old line...throws error
                        if (exists($o->{$name}) && (ref($o->{$name}) eq 'ARRAY')) {
                            push(@{$o->{$name}}, $data);
                        } else {
                            my $arrvalue = $o->{$name};
                            undef $o->{$name};
                            $o->{$name}[0] = $arrvalue;
                            push(@{$o->{$name}}, $data);
                        }
                    } else {
                        $o->{$name} = "", next if $data =~ /^\s*$/;
                        $o->{$name} = $data;
                    }
                    next;
                }
                
                foreach (@columns) {
                    my ($head, $val) = ( m/^([^=]+)="([^"]+)"$/ );
                    $o->{$name}{$head} = $val;
                }
                
                $o->{$name}{'Contents'} = $data;
            }
            undef $input;
        }
    }

    my ( $qs ) = ( $r->request_uri =~ m/\?(.*)/ );
    if ($qs) {
        $input .= "&" if ($input);
        $input .= $qs;
    }

    my @kv = split('&',$input);
    foreach (@kv) {
        my ($k,$v) = split('=');
        $k =~ s/\+/ /g;
        $k = unescape( $k );
        if ($v) {
            $v =~ s/\+/ /g;
            $v = unescape( $v );
        }
        if (defined $o->{$k}) {
            $o->{$k} .= "\0$v";
        } else {
            $o->{$k} = $v;
        }
    }

    return $o;
}

1;
