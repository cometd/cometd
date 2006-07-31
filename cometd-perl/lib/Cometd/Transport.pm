package Cometd::Transport;

sub TRANSPORTS() { 0 }
sub PRIORITIES() { 1 }
sub SESSION()    { 2 }

sub new {
    my $self = bless([
        { }, # transports
        [ ], # priorities
        undef, # session
    ], shift);
    
    my $session = POE::Session->create(
        object_states =>  [
            $self => {
                '_start'          =>  '_start',
                'process_plugins' =>  'process_plugins',
            },
        ],
    ) or die 'Unable to create a new session!';

    # save the session id
    $self->[ SESSION ] = $session->ID;

    $self;
}

sub _start {
    # TODO setup?
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
    my $tr = shift;
    
    my $t = $self->[ TRANSPORTS ];
    
    delete $t->{ $tr };
    
    # recalc priorities
    $self->[ PRIORITIES ] = sort {
        $t->{ $a }->{priority} <=> $t->{ $b }->{priority}
    } keys %{ $t };
}

sub process_plugins {
    my ( $kernel, $self, $i ) = @_[ KERNEL, OBJECT, ARG1 ];

    return unless ( @{ $self->[ PRIORITIES ] } );

    if ( defined $i && $#{ $self->[ PRIORITIES ] } > $i ) {
        return if ( $self->[ TRANSPORTS ]->{ $self->[ PRIORITIES ]->[ $i ] }->handle( @{ $_[ ARG0 ] } ) );
        $i++;
    } else {
        $i = 0;
    }
    
    $kernel->yield( process_plugins => $_[ ARG0 ] => $i );
}

1;
