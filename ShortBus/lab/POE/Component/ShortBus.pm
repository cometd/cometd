package POE::Component::ShortBus;

use warnings;
use strict;

# Untested because it's nearly 5am - Matt S Trout

#our $VERSION;
#$VERSION = do {my($r)=(q$Revision$=~/(\d+)/);sprintf"1.%04d",$r};

use Carp qw( carp croak );

use POE;

use constant SB_QUEUES        => 'queues';
use constant SB_NEXT_ID       => 'next_id';

use constant Q_EVENT_ID       => 'event_id';
use constant Q_QUEUE          => 'queue';
use constant Q_LISTENER       => 'listener';

# success
use constant SB_RC_OK         => 0;

# argument failure

use constant SB_RC_ARGS       => -1;

# queue already exists / no such queue
use constant SB_RC_EXISTS     => 1;
use constant SB_RC_NOSUCH     => 2;

# queue already connected / not connected
use constant SB_RC_ALREADY    => 3;
use constant SB_RC_NOCONN     => 4;

# queue destroy / listener detach
use constant SB_RC_DESTROY    => 5;
use constant SB_RC_DETACH     => 6;

# ack id too low/high
use constant SB_RC_EID_LOW    => 7;
use constant SB_RC_EID_HIGH   => 8;

sub new {
    my ($class, $params) = @_;

    my $self = bless { %{$params||{}} }, $class;

    $self->{+SB_QUEUES}  ||= {};
    $self->{+SB_NEXT_ID} ||= 0;

    $self->_session_start();

    $self;
}

sub _session_start {
    my $self = shift;

    POE::Session->create(
        object_states => [
            $self => {
                _start        => "_poe_start",
                shutdown      => "_poe_shutdown",

                create        => "_poe_create",
                post          => "_poe_post",
                destroy       => "_poe_destroy",

                connect       => "_poe_connect",
                disconnect    => "_poe_disconnect",
                acknowledge   => "_poe_acknowledge",
            },
        ],
    );

    undef;
}

# Session management.  When the session starts, set an alias on it to
# match the object.  Any object method can then post to the session with
# little or no fuss.

sub _poe_start {
    my ($self, $kernel) = @_[ OBJECT, KERNEL ];

    $kernel->alias_set("$self");
}

sub shutdown {
    my $self = $_[OBJECT];
    $poe_kernel->call("$self", "shutdown", @_[ARG0 .. $#_]);
}

sub _poe_shutdown {
    my ($self, $kernel) = @_[ OBJECT, KERNEL ];

    # Remove the current session's alias.
    $kernel->alias_remove("$self");

    undef;
}

# Queue creation and teardown

sub create {
    my $self = $_[OBJECT];
    $poe_kernel->call("$self", "create", @_[ARG0 .. $#_]);
}

sub _poe_create {
    my ($self, $kernel, $postback)
        = @_[ OBJECT, KERNEL, ARG0 ];

    unless (ref($postback) eq 'CODE') {
        return; # XXX
    }

    my $new_id = $self->{+SB_NEXT_ID}++;

    my $queue = $self->{+SB_QUEUES}{$new_id} = { };

    $queue->{+Q_EVENT_ID} = 0;
    $queue->{+Q_QUEUE} = []; 

    $postback->(+SB_RC_OK, $new_id);
}        

sub destroy {
    my $self = $_[OBJECT];
    $poe_kernel->call("$self", "destroy", @_[ARG0 .. $#_]);
}

sub _poe_destroy {
    my ($self, $kernel, $postback, $queue_id)
        = @_[ OBJECT, KERNEL, ARG0, ARG1 ];

    unless (ref($postback) eq 'CODE') {
        return; # XXX
    }

    unless (defined $queue_id) {
        $postback->(+SB_RC_ARGS);
        return;
    }

    if (my $queue = delete $self->{+SB_QUEUES}{$queue_id}) {

        if (my $listener = $queue->{+Q_LISTENER}) {

            $listener->(+SB_RC_DESTROY);
        }

        $postback->(+SB_RC_OK);

    } else {

        $postback->(+SB_RC_NOSUCH);
    }
}

# Posting messages to a queue

sub post {
    my $self = $_[OBJECT];
    $poe_kernel->call("$self", "post", @_[ARG0 .. $#_]);
}

sub _poe_post {
    my ($self, $kernel, $postback, $queue_id, $message_body)
        = @_[ OBJECT, KERNEL, ARG0, ARG1, ARG2 ];

    unless (ref($postback) eq 'CODE') {
        return; # XXX
    }

    unless (defined $queue_id && defined $message_body) {
        $postback->(+SB_RC_ARGS);
        return;
    }

    if (my $queue = $self->{+SB_QUEUES}{$queue_id}) {

        push(@{$queue->{+Q_QUEUE}}, $message_body);

        my $event_id = $queue->{+Q_EVENT_ID} + scalar(@{$queue->{+Q_QUEUE}});

        if (my $listener = $queue->{+Q_LISTENER}) {

            $listener->(+SB_RC_OK, $event_id, $message_body);
        }

        $postback->(+SB_RC_OK, $event_id);

    } else {

        $postback->(+SB_RC_NOSUCH);
    }
}

# Connect and disconnect

sub connect {
    my $self = $_[OBJECT];
    $poe_kernel->call("$self", "connect", @_[ARG0 .. $#_]);
}

sub _poe_connect {
    my ($self, $kernel, $postback, $queue_id, $listener)
        = @_[ OBJECT, KERNEL, ARG0, ARG1, ARG2 ];

    unless (ref($postback) eq 'CODE') {
        return; # XXX
    }

    unless (defined $queue_id && (ref($listener) eq 'CODE')) {
        $postback->(+SB_RC_ARGS);
        return;
    }

    if (my $queue = $self->{+SB_QUEUES}{$queue_id}) {

        if (exists $queue->{+Q_LISTENER}) {

            $postback->(+SB_RC_ALREADY);

        } else {

            $queue->{+Q_LISTENER} = $listener;

            my $event_id = $queue->{+Q_EVENT_ID};

            foreach my $message (@{$queue->{+Q_QUEUE}}) {

                $listener->(+SB_RC_OK, ++$event_id, $message);
            }

            $postback->(+SB_RC_OK);
        }

    } else {

        $postback->(+SB_RC_NOSUCH);
    }
}

sub disconnect {
    my $self = $_[OBJECT];
    $poe_kernel->call("$self", "disconnect", @_[ARG0 .. $#_]);
}

sub _poe_disconnect {
    my ($self, $kernel, $postback, $queue_id)
        = @_[ OBJECT, KERNEL, ARG0, ARG1 ];

    unless (ref($postback) eq 'CODE') {
        return; # XXX
    }

    unless (defined $queue_id) {
        $postback->(+SB_RC_ARGS);
        return;
    }

    if (my $queue = $self->{+SB_QUEUES}{$queue_id}) {

        if (my $listener = delete $queue->{+Q_LISTENER}) {

            $listener->(+SB_RC_DETACH);
            $postback->(+SB_RC_OK);

        } else {

            $postback->(+SB_RC_NOCONN);
        }

    } else {

        $postback->(+SB_RC_NOSUCH);
    }
}

# Acking delivered messages

sub acknowledge {
    my $self = $_[OBJECT];
    $poe_kernel->call("$self", "acknowledge", @_[ARG0 .. $#_]);
}

sub _poe_acknowledge {
    my ($self, $kernel, $postback, $queue_id, $event_id)
        = @_[ OBJECT, KERNEL, ARG0, ARG1, ARG2 ];

    unless (ref($postback) eq 'CODE') {
        return; # XXX
    }

    unless (defined $queue_id && defined $event_id) {
        $postback->(+SB_RC_ARGS);
        return;
    }

    if (my $queue = $self->{+SB_QUEUES}{$queue_id}) {

        my $current_id = $queue->{+Q_EVENT_ID};

        if ($event_id < $current_id) {

            $postback->(+SB_RC_EID_LOW);

        } elsif ($event_id == $current_id) {

           $postback->(+SB_RC_OK); # Dupe ack, no worries

        } else {

            my $clear = $event_id - $current_id;

            if ($clear > scalar(@{$queue->{+Q_QUEUE}})) {

                $postback->(+SB_RC_EID_HIGH);

            } else {

                splice(@{$queue->{+Q_QUEUE}}, 0, $clear);
                $queue->{+Q_EVENT_ID} = $event_id;

                $postback->(+SB_RC_OK);
            }
        }
    }
}

our $AUTOLOAD;
sub AUTOLOAD {
    my $self = shift;
    warn "$self->$AUTOLOAD(@_)";
}

1;

