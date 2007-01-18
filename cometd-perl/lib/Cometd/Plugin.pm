package Cometd::Plugin;

use Class::Accessor;
use base qw(Class::Accessor);

__PACKAGE__->mk_accessors( qw( plugin_name ) );

use Scalar::Util qw( weaken );

use overload '""' => sub { shift->as_string(); };

use strict;
use warnings;

sub new {
    my $class = shift;
    bless( { @_ }, ref $class || $class );
}

sub as_string {
    warn "This cometd plugin should of been subclassed!";
    __PACKAGE__;
}

sub handle_event {
    my ( $self, $event ) = @_;
    
    #$self->$event( @_[ 2, $#_ ] )
    return $self->$event( splice( @_, 2, $#_ ) )
        if ( $self->can( $event ) );
    
    return undef;
}

sub cometd_component {
    my $self = shift;
    if ( my $comp = shift ) {
        $self->{_comp} = $comp;
        weaken( $self->{_comp} );
    }
    return $self->{_comp};
}

sub _log {
    my $self = shift;
    if ( my $comp = $self->cometd_component ) {
        # add one level to caller()
        $comp->_log(@_, l => 1);
    } else {
        my %o = @_;
    #    return unless ( $o{v} <= ( $self->{LogLevel} || 10 ) );
        my $sender = ( defined $self->{heap} && defined $self->{heap}->{addr} )
            ? $self->{heap}->{addr} : "?";
        my $l = $o{l} ? $o{l}+1 : 1;
        my $caller = (caller($l))[3] || '?';
        $caller =~ s/POE::Component/PoCo/;
        print STDERR '['.localtime()."][?][$caller][$sender] $o{msg}\n";
    }
}

# Plugins can define the following methods
# all are optional

# ---------------------------------------------------------
# server
# ---------------------------------------------------------
# local_connected
# local_receive
# local_disconnected

# ---------------------------------------------------------
# client
# ---------------------------------------------------------
# remote_connected
# remote_receive
# remote_disconnected (TODO not setup yet)

1;
