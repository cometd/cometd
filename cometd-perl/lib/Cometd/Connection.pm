package Cometd::Connection;

use Sprocket qw( Connection );
use POE;

use base 'Sprocket::Connection';

use strict;
use warnings;

sub add_client {
    my $self = shift;

    if ( $self->clid && $self->event_manager ) {
        $self->{destroy_events}->{remove_client} = $poe_kernel->call(
            $self->event_manager => add_client => $self->clid => $self->ID
        );
    }
}

sub remove_client {
    my $self = shift;

    $poe_kernel->call( $self->event_manager => remove_client => $self->clid )
        if ( $self->clid && $self->event_manager );
}

sub add_channels {
    my $self = shift;

    if ( my ( $channels ) = @_ ) {
        foreach ( @$channels ) {
            $self->{channels}->{$_} = 1;
        }

        $poe_kernel->call( $self->event_manager => add_channels => $self->clid => $channels )
            if ( $self->clid && $self->event_manager );
    }

    return ( keys %{$self->{channels}} );
}

sub remove_channels {
    my $self = shift;

    if ( my ( $channels ) = @_ ) {
        foreach ( @$channels ) {
            delete $self->{channels}->{$_};
        }

        $poe_kernel->call( $self->event_manager => remove_channels => $self->clid => $channels )
            if ( $self->clid && $self->event_manager );
    }

    return ( keys %{$self->{channels}} );
}

sub clid {
    my ( $self, $clid ) = @_;

    if ( $clid ) {
        $self->{clid} = $clid;
        $self->add_client();
    }

    return $self->{clid};
}

sub send_event {
    my $self = shift;

    $poe_kernel->call( $self->event_manager => deliver_events => Sprocket::Event->new( clientId => $self->clid => @_ ) );
}

sub get_events {
    my $self = shift;

    $poe_kernel->call( $self->event_manager => get_events => $self->clid => [
        $self->parent_id, $self->event( 'events_received' )
    ] );
}

1;
