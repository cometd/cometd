package Cometd::Transport;

use POE;

use strict;
use warnings;

sub TRANSPORTS() { 0 }
sub PRIORITIES() { 1 }
sub SESSIONID()  { 2 }
sub PARENTID()   { 3 }

sub EVENT_NAME() { 0 }
sub SERVER()     { 1 }
sub CONNECTION() { 2 }

sub new {
    my $class = shift;
    my %opts = @_;
    
    my $self = bless([
        { },              # transports
        [ ],              # priorities
        undef,            # session
        $opts{parent_id}, # parent
    ], ref $class || $class );
    
    # save the session id
    $self->[ SESSIONID ] =
    POE::Session->create(
        object_states =>  [
            $self => {
                _start          =>  '_start',
                process_plugins =>  '_process_plugins',
                _stop           =>  '_stop',
                sig_die         =>  '_exception',
            },
        ],
    )->ID();

    return $self;
}

sub _start {
    my ($self, $kernel) = @_[OBJECT, KERNEL];
    $kernel->alias_set( "$self" );
    $kernel->sig( DIE => 'sig_die' );
}

sub _stop {
    
}

sub _log {
    $poe_kernel->call( shift->[ PARENTID ] => _log => @_ );
}

sub _exception {
    my ($kernel, $self, $sig, $error) = @_[KERNEL, OBJECT, ARG0, ARG1];
    $self->_log(v => 1, l => 1, msg => "plugin exception handled: ($sig) : "
        .join(' | ',map { $_.':'.$error->{$_} } keys %$error ) );
    $kernel->sig_handled();
}

sub add_transport {
    my $self = shift;
    
    my $t = $self->[ TRANSPORTS ];
   
    my ( $parent_id, $plugin, $pri ) = @_;
    my $name = $plugin->name();
    
    warn "WARNING : Overwriting existing plugin '$name' (You have two plugins with the same name)"
        if ( exists( $t->{ $name } ) );

    $t->{ $name } = {
        plugin => $plugin,
        priority => $pri || 0,
    };
    
    $plugin->parent_id( $parent_id );
    
    $plugin->add_plugin( $self )
        if ( $plugin->can( 'add_plugin' ) );
    
    # recalc priorities
    @{ $self->[ PRIORITIES ] } = sort {
        $t->{ $a }->{priority} <=> $t->{ $b }->{priority}
    } keys %{ $t };

    return 1;
}

sub remove_transport {
    my $self = shift;
    my $tr = shift;
    
    # TODO delete by name or obj
    
    my $t = $self->[ TRANSPORTS ];
    
    my $plugin = delete $t->{ $tr };
    
    $plugin->remove_plugin( $self )
        if ( $plugin->can( 'remove_plugin' ) );
    
    # recalc priorities
    @{ $self->[ PRIORITIES ] } = sort {
        $t->{ $a }->{priority} <=> $t->{ $b }->{priority}
    } keys %{ $t };
}

sub _process_plugins {
    my ( $kernel, $self, $i ) = @_[ KERNEL, OBJECT, ARG1 ];

    return unless ( @{ $self->[ PRIORITIES ] } );
   
    if ( my $t = $_[ ARG0 ]->[ CONNECTION ]->plugin() ) {
        return $self->[ TRANSPORTS ]->{ $t }->{plugin}->handle_event( @{ $_[ ARG0 ] } );
    } else {
        if ( defined $i && $#{ $self->[ PRIORITIES ] } >= $i ) {
            return if ( $self->[ TRANSPORTS ]->{
                    $self->[ PRIORITIES ]->[ $i ]
                }->{plugin}->handle_event( @{ $_[ ARG0 ] } ) );
            $i++;
            # avoid a yield
            return if ( $#{ $self->[ PRIORITIES ] } < $i );
        } else {
            $i = 0;
        }
    }
    
    $kernel->yield( process_plugins => $_[ ARG0 ] => $i );
}

sub process_plugins {
    my $self = shift;
    $poe_kernel->call( $self->[ SESSIONID ] => process_plugins => @_ );
}

sub yield {
    my $self = shift;
    $poe_kernel->post( $self->[ SESSIONID ] => @_ );
}

sub name {
    my $self = shift;
    my @list;
    foreach my $t (keys %{ $self->[ TRANSPORTS ] }) {
        push(@list, $self->[ TRANSPORTS ]->{ $t }->{plugin}->name() );
    }
    return "Transport for plugins: ".join(',',@list);
}

1;
