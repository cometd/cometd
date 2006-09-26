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

    return unless (my $c = $obj->{channel});
   
    print "channel: $c\n";

    return if ( $c =~ '/meta/ping' );
    
    # FIXME more error checking
    if ( $c =~ '/meta/connect' ) {
        return unless (ref($obj->{data}{channels}) eq 'ARRAY');

        foreach my $ch (@{$obj->{data}{channels}}) {
            $self->{ch}->{$ch}++;
        }
        return;
    }
    
    if ( $c =~ '/meta/disconnect' ) {
        return unless (ref($obj->{data}{channels}) eq 'ARRAY');
        
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
