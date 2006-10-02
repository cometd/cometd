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
    my ( $self, $cheap, $event ) = @_;
    warn "event:".Data::Dumper->Dump([$event]);

    unless ( ref($event) eq 'HASH' ) {
        warn 'event is not a hash ref';
        return;
    }
    
    return unless (my $c = $event->{channel});
   
    return if ( $c =~ '/meta/ping' );
    
    my $addr = $cheap->{addr};
    
    if ( $c eq '/meta/connect' ) {
        return unless ($event->{data} && ref($event->{data}) eq 'HASH'
            && ref($event->{data}->{channels}) eq 'ARRAY');

        foreach my $ch (@{$event->{data}->{channels}}) {
            if ( !$self->{ch}->{$ch} ) {
                $self->{ch}->{$ch} = { $addr => 1 };
            } else {
                $self->{ch}->{$ch}->{$addr}++;
            }
        }
        return;
    }
    
    if ( $c eq '/meta/disconnect' ) {
        return unless ($event->{data} && ref($event->{data}) eq 'HASH'
            && ref($event->{data}->{channels}) eq 'ARRAY');
        
        foreach my $ch (@{$event->{data}->{channels}}) {
            next if ( !$self->{ch}->{$ch} );
            $self->{ch}->{$ch}->{$addr}--;
            delete $self->{ch}->{$ch}->{$addr} if ( $self->{ch}->{$ch}->{$addr} <= 0 );
            delete $self->{ch}->{$ch} if ( !scalar( keys %{$self->{ch}->{$ch}} ) );
        }
        return;
    }

    if ( $c =~ m~/meta/~ ) {
        warn "unhandled meta channel:$c";
        return;
    }

    # XXX check if this is client is allowed to send events
    POE::Component::Cometd::Client::deliver_event( $cheap, $event );
    
    return;
}

1;
