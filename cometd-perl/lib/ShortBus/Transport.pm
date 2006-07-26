package Cometd::Transport;

sub TRANSPORTS() { 0 }
sub PRIORITIES() { 1 }

sub new {
    bless([
        { }, # transports
        [ ], # priorities
    ], shift);
}

sub add_transport {
    my $self = shift;
    my $name = shift;
    
    my $t = $self->[ TRANSPORTS ];
    
    $t->{ $name } = {
        priority => shift,
        plugin => shift,
    };

    # recalc priorities
    $self->[ PRIORITIES ] = sort {
        $t->{ $a }->{priority} <=> $t->{ $b }->{priority}
    } keys %{ $t };
    
    return 1;
}

sub remove_transport {
    my $self = shift;
    
    delete $self->[ TRANSPORTS ]->{ shift };
}

sub process_plugins {
    my $self = shift;

    foreach $t (@{ $self->[ PRIORITIES ] }) {
        last if ( $self->[ TRANSPORTS ]->{ $t }->handle( @_ ) );
    }
}


1;
