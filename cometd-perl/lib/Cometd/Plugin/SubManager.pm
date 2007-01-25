package Cometd::Plugin::ChannelManager;

use strict;
use warnings;

use Scalar::Util qw( weaken );

sub new {
    my $class = shift;
    bless({
        ch => {},
        cid => {},
        comp => undef,
        @_
    }, ref $class || $class );
}

sub set_comp {
    my $self = shift;
    $self->{comp} = shift;

    weaken( $self->{comp} );

    $self->_log(v => 4, msg => "Channel Mangaer Initialized");
}

sub _log {
    my $self = shift;
    if ( my $comp = $self->{comp} ) {
        # add one level to caller()
        $comp->_log(@_, l => 1);
    } # XXX else
}

sub deliver {
    my ( $self, $cheap, $event ) = @_;
    warn "event:".Data::Dumper->Dump([$event]);

    unless ( ref($event) eq 'HASH' ) {
        warn 'event is not a hash ref';
        return;
    }
    
    return unless (my $c = $event->{channel});
   
    # XXX
    return if ( $c =~ '/meta/ping' );
    
    my $addr = $cheap->{addr};
    my $cid = $event->{clientId};
    
    if ( $c eq '/meta/subscribe' || $c eq '/meta/unsubscribe' ) {
        return unless ($event->{subscription} && $cid);
        
        warn "$c channel $event->{subscription}";
        if ( $self->{cid}{ $cid } ) {
            if ( $c eq '/meta/subscribe' ) {
                my %dd;
                $self->{cid}{ $cid }->{ch} = [
                    grep { !$dd{$_}++ } ( @{ $self->{cid}{ $cid }->{ch} }, $event->{subscription} )
                ];
            } else {
                @{ $self->{cid}{ $cid }->{ch} }
                    = grep { $_ ne $event->{subscription} } @{ $self->{cid}{ $cid }->{ch} };
            }
            $self->{cid}{ $cid }->{t} = time();
        } else {
            $self->{cid}{ $cid } = {
                ch => [ ( $c eq '/meta/subscribe' ) ? $event->{subscription} : () ],
                t => time(), # XXX expire
            };
        }
        return;
    }
   

    
    if ( $c eq '/meta/connect' || $c eq '/meta/reconnect' ) {

        if ($event->{data} && ref($event->{data}) eq 'HASH'
            && ref($event->{data}->{channels}) eq 'ARRAY') {

            foreach my $ch (@{$event->{data}->{channels}}) {
                if ( !$self->{ch}->{$ch} ) {
                    $self->{ch}->{$ch} = { $addr => 1 };
                } else {
                    $self->{ch}->{$ch}->{ $addr } = 1;
                }
            }
        
            if ( $self->{cid}{ $cid } ) {
                if ( $self->{cid}{ $cid }->{ch} ) {
                    my %dd;
                    $self->{cid}{ $cid }->{ch} = [
                        grep { !$dd{$_}++ } ( @{$event->{data}{channels}}, @{ $self->{cid}{ $cid }->{ch} } )
                    ];
                } else {
                    $self->{cid}{ $cid }->{ch} = [ @{$event->{data}{channels}} ];
                }
                # XXX dedupe
                $self->{cid}{ $cid }->{t} = time();
            } else {
                $self->{cid}{ $cid } = {
                    ch => [ @{$event->{data}{channels}} ],
                    t => time(),
                };
            }
        }

        if ( !$self->{cid}{ $cid } ) {
            $self->{cid}{ $cid } = {
                ch => [],
                t => time(),
            };
        }

        # send an internal event to give perlbal the list of channels
        $self->{comp}->deliver_event( {
            channel => '/meta/internal/reconnect',
            clientId => $cid,
            channels => $self->{cid}{ $cid }->{ch} || [],
        }, $cheap, 1 ) if ($self->{comp});
        
        return;
    }
    
    if ( $c eq '/meta/disconnect' ) {
        return unless ($event->{data} && $cid
            && ref($event->{data}) eq 'HASH'
            && ref($event->{data}->{channels}) eq 'ARRAY');
        
        delete $self->{cid}{ $cid };
        
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

    # XXX check if this client is allowed to send events

    $self->{comp}->deliver_event( $event, $cheap ) if ($self->{comp});
    
    return;
}

1;
