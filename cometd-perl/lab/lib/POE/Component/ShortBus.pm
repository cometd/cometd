package POE::Component::ShortBus;

use warnings;
use strict;

# Untested because it's nearly 5am - Matt S Trout

#our $VERSION;
#$VERSION = do {my($r)=(q$Revision$=~/(\d+)/);sprintf"1.%04d",$r};

use Carp qw( carp croak );

use POE;

use base qw/Exporter/;

use constant SB_SESSION_ID    => 'session_id';

use constant SB_QUEUES        => 'queues';
use constant SB_NEXT_ID       => 'next_id';

use constant SB_Q_EVENT_ID    => 'event_id';
use constant SB_Q_QUEUE       => 'queue';
use constant SB_Q_LISTENER    => 'listener';

our @EXPORT = map { 'SB_RC_'.$_ }
                  qw/OK ARGS EXISTS NOSUCH ALREADY NOCONN DESTROY DETACH EID_LOW EID_HIGH/;

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

    $self->{+SB_SESSION_ID} = POE::Session->create(
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
    )->ID;

    undef;
}

sub ID {
    return shift->{+SB_SESSION_ID};
}

# Session management.  When the session starts, set an alias on it to
# match the object.  Any object method can then post to the session with
# little or no fuss.

sub _poe_start {
    my ($self, $kernel) = @_[ OBJECT, KERNEL ];

    $kernel->alias_set("$self");
}

sub shutdown {
    my $self = shift;
    $poe_kernel->call("$self", "shutdown", @_);
}

sub _poe_shutdown {
    my ($self, $kernel) = @_[ OBJECT, KERNEL ];

    # Remove the current session's alias.
    $kernel->alias_remove("$self");

    undef;
}

# Queue creation and teardown

sub create {
    my $self = shift;
    $poe_kernel->call("$self", "create", @_);
}

sub _poe_create {
    my ($self, $kernel, $postback)
        = @_[ OBJECT, KERNEL, ARG0 ];

    unless (ref($postback) eq 'CODE') {
        $postback = $_[SENDER]->postback($postback || "create");
    }

    my $new_id = $self->{+SB_NEXT_ID}++;

    my $queue = $self->{+SB_QUEUES}{$new_id} = { };

    $queue->{+SB_Q_EVENT_ID} = 0;
    $queue->{+SB_Q_QUEUE} = []; 

    $postback->(+SB_RC_OK, $new_id);
    return ( +SB_RC_OK, $new_id );
}        

sub destroy {
    my $self = shift;
    $poe_kernel->call("$self", "destroy", @_);
}

sub _poe_destroy {
    my ($self, $kernel, $postback, $queue_id)
        = @_[ OBJECT, KERNEL, ARG0, ARG1 ];

    unless (ref($postback) eq 'CODE') {
        $postback = $_[SENDER]->postback($postback || "destroy");
    }

    unless (defined $queue_id) {
        $postback->(+SB_RC_ARGS);
        return +SB_RC_ARGS;
    }

    if (my $queue = delete $self->{+SB_QUEUES}{$queue_id}) {

        if (my $listener = $queue->{+SB_Q_LISTENER}) {

            $listener->(+SB_RC_DESTROY);
        }

        $postback->(+SB_RC_OK);
        return +SB_RC_OK;
        
    } else {

        $postback->(+SB_RC_NOSUCH);
        return +SB_RC_NOSUCH;
        
    }
}

# Posting messages to a queue

sub post {
    my $self = shift;
    $poe_kernel->call("$self", "post", @_);
}

sub _poe_post {
    my ($self, $kernel, $postback, $queue_id, $message_body)
        = @_[ OBJECT, KERNEL, ARG0, ARG1, ARG2 ];

    unless (ref($postback) eq 'CODE') {
        $postback = $_[SENDER]->postback($postback || "post");
    }

    unless (defined $queue_id && defined $message_body) {
        $postback->(+SB_RC_ARGS);
        return +SB_RC_ARGS;
    }

    if (my $queue = $self->{+SB_QUEUES}{$queue_id}) {

        push(@{$queue->{+SB_Q_QUEUE}}, $message_body);

        my $event_id = $queue->{+SB_Q_EVENT_ID} + scalar(@{$queue->{+SB_Q_QUEUE}});

        if (my $listener = $queue->{+SB_Q_LISTENER}) {

            $listener->(+SB_RC_OK, $event_id, $message_body);
        }

        $postback->(+SB_RC_OK, $event_id);
        return ( +SB_RC_OK, $event_id );
        
    } else {

        $postback->(+SB_RC_NOSUCH);
        return +SB_RC_NOSUCH;
        
    }
}

# Connect and disconnect

sub connect {
    my $self = shift;
    $poe_kernel->call("$self", "connect", @_);
}

sub _poe_connect {
    my ($self, $kernel, $postback, $queue_id, $listener)
        = @_[ OBJECT, KERNEL, ARG0, ARG1, ARG2 ];

    unless (ref($postback) eq 'CODE') {
        $postback = $_[SENDER]->postback($postback  || "connect");
    }

    unless (defined $queue_id && (ref($listener) eq 'CODE')) {
        $postback->(+SB_RC_ARGS);
        return +SB_RC_ARGS;
    }

    if (my $queue = $self->{+SB_QUEUES}{$queue_id}) {

        if (exists $queue->{+SB_Q_LISTENER}) {

            $postback->(+SB_RC_ALREADY);
            return +SB_RC_ALREADY;
            
        } else {

            $queue->{+SB_Q_LISTENER} = $listener;

            my $event_id = $queue->{+SB_Q_EVENT_ID};

            foreach my $message (@{$queue->{+SB_Q_QUEUE}}) {

                $listener->(+SB_RC_OK, ++$event_id, $message);
            }

            $postback->(+SB_RC_OK);
            return +SB_RC_OK;
            
        }

    } else {

        $postback->(+SB_RC_NOSUCH);
        return +SB_RC_NOSUCH;
        
    }
}

sub disconnect {
    my $self = shift;
    $poe_kernel->call("$self", "disconnect", @_);
}

sub _poe_disconnect {
    my ($self, $kernel, $postback, $queue_id)
        = @_[ OBJECT, KERNEL, ARG0, ARG1 ];

    unless (ref($postback) eq 'CODE') {
        $postback = $_[SENDER]->postback($postback || "disconnect");
    }

    unless (defined $queue_id) {
        $postback->(+SB_RC_ARGS);
        return +SB_RC_ARGS;
    }

    if (my $queue = $self->{+SB_QUEUES}{$queue_id}) {

        if (my $listener = delete $queue->{+SB_Q_LISTENER}) {

            $listener->(+SB_RC_DETACH);
            $postback->(+SB_RC_OK);
            return +SB_RC_OK;
            
        } else {

            $postback->(+SB_RC_NOCONN);
            return +SB_RC_NOCONN;
            
        }

    } else {

        $postback->(+SB_RC_NOSUCH);
        return +SB_RC_NOSUCH;
        
    }
}

# Acking delivered messages

sub acknowledge {
    my $self = shift;
    $poe_kernel->call("$self", "acknowledge", @_);
}

sub _poe_acknowledge {
    my ($self, $kernel, $postback, $queue_id, $event_id)
        = @_[ OBJECT, KERNEL, ARG0, ARG1, ARG2 ];

    unless (ref($postback) eq 'CODE') {
        $postback = $_[SENDER]->postback($postback || "acknowledge");
    }

    unless (defined $queue_id && defined $event_id) {
        $postback->(+SB_RC_ARGS);
        return +SB_RC_ARGS;
    }

    if (my $queue = $self->{+SB_QUEUES}{$queue_id}) {

        my $current_id = $queue->{+SB_Q_EVENT_ID};

        if ($event_id < $current_id) {

            $postback->(+SB_RC_EID_LOW);
            return +SB_RC_EID_LOW;
            
        } elsif ($event_id == $current_id) {

            $postback->(+SB_RC_OK); # Dupe ack, no worries
            return +SB_RC_OK;
            
        } else {

            my $clear = $event_id - $current_id;

            if ($clear > scalar(@{$queue->{+SB_Q_QUEUE}})) {

                $postback->(+SB_RC_EID_HIGH);
                return +SB_RC_EID_HIGH;
                
            } else {

                splice(@{$queue->{+SB_Q_QUEUE}}, 0, $clear);
                $queue->{+SB_Q_EVENT_ID} = $event_id;

                $postback->(+SB_RC_OK);
                return +SB_RC_OK;
                
            }
        }
    }
}

1;
