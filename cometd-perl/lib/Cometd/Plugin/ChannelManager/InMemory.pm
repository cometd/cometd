package Cometd::Plugin::ChannelManager::InMemory;
# simple in memory channel manager

use strict;
use warnings;
use Data::Dumper;

sub new {
    my $class = shift;
    bless({
        ch => {},
        @_
    }, $class);
}

sub deliver {
    my ( $self, $cheap, $obj ) = @_;
    warn "obj:".Data::Dumper->Dump([$obj]);

    # FIXME currently $obi is plain json, the JSONTransport needs to do the conversion first
    return;
    
    my $c = $obj->{channel};
    return if ( $c =~ '/meta/ping' );
    
    # FIXME more error checking
    if ( $c =~ '/meta/connect' ) {
        foreach my $ch (@{$obj->{data}{channels}}) {
            $self->{ch}->{$ch}++;
        }
        return;
    }
    
    if ( $c =~ '/meta/disconnect' ) {
        foreach my $ch (@{$obj->{data}{channels}}) {
            $self->{ch}->{$ch}--;
            delete $self->{ch}->{$ch} if ( $self->{ch}->{$ch} <= 0 );
        }
        return;
    }

    if ( $self->{ch}->{$c} ) {
        
    }
    
    # TODO locate the server that has the $obj->{channel}
    # and deliver it there
}

1;
