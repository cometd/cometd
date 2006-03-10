package POE::Component::ShortBus;

use warnings;
use strict;

#our $VERSION;
#$VERSION = do {my($r)=(q$Revision$=~/(\d+)/);sprintf"1.%04d",$r};

use Carp qw( carp croak );

use Socket qw( INADDR_ANY inet_ntoa );

use POE qw( Wheel::ReadWrite );

# constants
use constant SVR_PORT_DFLT => 8081;
use constant SVR_ADDR_BIND => "server.addr.bind";
use constant SVR_PORT_BIND => "server.port.bind";
use constant SVR_WHEEL     => "server.wheel";
use constant LST_BY_ID     => "listeners.by_id";
use constant LST_BY_EVENT  => "listeners.by_event";
use constant TIMERS        => "timers";

sub new {
    my ($class, $params) = @_;

    my $self = bless { }, $class;

    $self->{+SVR_ADDR_BIND} = delete($params->{BindAddress})
        if exists $params->{BindAddress};
    
    $self->{+SVR_PORT_BIND} = delete($params->{BindPort})
        if exists $params->{BindPort};

    $self->{+SVR_ADDR_BIND} ||= inet_ntoa(INADDR_ANY);
    $self->{+SVR_PORT_BIND} ||= SVR_PORT_DFLT;

    $self->_session_start();

    $self->_plugins_start();

    $self;
}


sub _mutator {
    my ($self, $field) = @_;
     
    if (@_ == 3) {
        my $new_value = $self->{$field} = $_[2];
        $self->save();
        return $new_value;
    }
     
    return $self->{$field} if @_ == 2;
    croak "Bad number of parameters";
}

sub bind_address {
    my $self = shift;
    return $self->_mutator(SVR_ADDR_BIND, @_);
}

sub bind_port {
    my $self = shift;
    return $self->_mutator(SVR_PORT_BIND, @_);
}

sub _session_start {
    my $self = shift;

    POE::Session->create(
        object_states => [
            $self => {
                _start        => "_poe_start",
                shutdown      => "_poe_shutdown",

                register      => "_poe_register",
                unregister    => "_poe_unregister",

                # Event notification.
                notify        => "_poe_notify",
                
                plugin_start  => "_poe_plugins_start",
                add_plugin    => "_poe_add_plugin",
            },
        ],
    );

    undef;
}

sub shutdown {
    my $self = shift;
    $poe_kernel->call("$self", "shutdown");
}

sub _poe_shutdown {
    my ($self, $kernel) = @_[ OBJECT, KERNEL ];

    # Tell the HTTP client to go away.
    $kernel->post("ua($self)" => "shutdown");

    $self->_listener_stop();

    foreach my $id (keys %{$self->{+LST_BY_ID}}) {
        $self->_remove_listener($id);
    }

    # Remove peer checking timer.
    if ($self->{+TIMERS}) {
        $kernel->alarm_remove($self->{+TIMERS});
        $self->{+TIMERS} = undef;
    }

    # Remove the current session's alias.
    $kernel->alias_remove("$self");

    undef;
}

# Session management.  When the session starts, set an alias on it to
# match the object.  Any object method can then post to the session with
# little or no fuss.

sub _poe_start {
    my ($self, $kernel) = @_[ OBJECT, KERNEL ];

    $kernel->alias_set("$self");
}

sub _plugins_start {
    my $self = shift;
    $poe_kernel->call("$self", "plugins_start", @_);
}

sub _poe_plugins_start {
    my ($self, $kernel, $sender) = @_[ OBJECT, KERNEL, SENDER ];
    # needed?
}

sub add_plugin {
    my $self = shift;
    $poe_kernel->call("$self", "add_plugin", @_);
}

sub _poe_add_plugin {
    my ($self, $kernel, $plugin) = @_[ OBJECT, KERNEL, ARG0 ];
    eval "use $plugin;";
    if ($@) {
        warn $@;
    }        
}

sub register {
    my $self = shift;
    $poe_kernel->call("$self", "register", @_);
}

sub _poe_register {
    my ($self, $kernel, $sender) = @_[ OBJECT, KERNEL, SENDER ];
    $kernel->refcount_increment($sender->ID, "$self");
    $self->{+LST_BY_ID}{$sender->ID} = { };
}

sub unregister {
    my $self = shift;
    $poe_kernel->call("$self", "unregister", @_);
}

sub _poe_unregister {
    my ($self, $kernel, $sender) = @_[ OBJECT, KERNEL, SENDER ];

    # TODO - Selectively remove events rather than deleting them all.
    $self->{+LST_BY_ID}{$sender->ID} = { };

    unless (keys %{$self->{+LST_BY_ID}{$sender->ID}}) {
        $self->_remove_listener($sender->ID);
    }
}

sub _remove_listener {
    my ($self, $sender_id) = @_;
    delete $self->{+LST_BY_ID}{$sender_id};
    $poe_kernel->refcount_decrement($sender_id, $self);
}

sub notify {
    my ($self, $event, @parameters) = @_;
    $poe_kernel->post("$self", "notify", $event, @parameters);
}

sub _poe_notify {
    my ($self, $kernel, $event, @parameters) = @_[
        OBJECT, KERNEL, ARG0 .. $#_
    ];

    foreach my $session_id (keys %{$self->{+LST_BY_ID}}) {
        # TODO - Determine whether the session cares.
        $kernel->post($session_id, $event, @parameters);
    }
}

our $AUTOLOAD;
sub AUTOLOAD {
    my $self = shift;
    warn "$self->$AUTOLOAD(@_)";
}

1;

