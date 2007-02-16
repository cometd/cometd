package POE::Component::EasyDBI;

use strict;
use warnings FATAL =>'all';

# Initialize our version
our $VERSION = '1.16';

# Import what we need from the POE namespace
use POE;
use POE::Session;
use POE::Filter::Reference;
use POE::Filter::Line;
use POE::Wheel::Run;
use POE::Component::EasyDBI::SubProcess;

use Params::Util qw( _ARRAY _HASH _CODE );

# Miscellaneous modules
use Carp;
use vars qw($AUTOLOAD);

our %COMMANDS = map { $_ => 1 } qw(                    
    commit
    rollback
    begin_work
    func
    method
    insert
    do
    single
    quote
    arrayhash
    hashhash
    hasharray
    array
    arrayarray
    hash
    keyvalhash
);

sub AUTOLOAD {
    my $self = shift;
    my $method = $AUTOLOAD;
    $method =~ s/.*:://;
    return unless $method =~ /[^A-Z]/;
    
    croak "EasyDBI command $method does not exist"
        unless ( exists( $COMMANDS{ $method } ) );

    $poe_kernel->call($self->{ID} => $method => { @_ });
}

# TODO use constants?
sub MAX_RETRIES () { 5 }
sub DEBUG () { 0 }

# Autoflush on STDOUT
# Select returns last selected handle
# So, reselect it after selecting STDOUT and setting Autoflush
#select((select(STDOUT), $| = 1)[0]);

sub spawn {
    my ($self,$session) = &new;
    return $session;
}

sub create {
    &spawn;
}

sub new {
    my $class = shift;

    # The options hash
    my %opt;

    # Support passing in a hash ref or a regular hash
    if ((@_ & 1) && _HASH($_[0])) {
        %opt = %{$_[0]};
    } else {
        # Sanity checking
        if (@_ & 1) {
            croak('POE::Component::EasyDBI requires an even number of options '
            .'passed to new()/spawn() call');
        }
        %opt = @_;
    }

    # lowercase keys
    %opt = map { lc($_) => $opt{$_} } keys %opt;
    
    my @valid = qw(
        dsn
        username
        password
        options
        alias
        max_retries
        ping_timeout
        use_cancel
        no_connect_failures
        connect_error
        reconnect_wait
        connected
        no_warnings
        sig_ignore_off
        no_cache
        alt_fork
        stopwatch
    );
    
    # check the DSN
    # username/password/port other options
    # should be part of the DSN
    if (!exists($opt{dsn})) {
        croak('DSN is required to spawn a new POE::Component::EasyDBI '
        .'instance!');
    }

    # check the USERNAME
    if (!exists($opt{username})) {
        croak('username is required to spawn a new POE::Component::EasyDBI '
        .'instance!');
    }

    # check the PASSWORD
    if (!exists($opt{password})) {
        croak('password is required to spawn a new POE::Component::EasyDBI '
            .'instance!');
    }

    # check the reconnect wait time
    if (exists($opt{reconnect_wait}) && $opt{reconnect_wait} < 1) {
        warn('A reconnect_wait of less than 1 second is NOT recommended, '
        .'continuing anyway');
    } elsif (!$opt{reconnect_wait}) {
        $opt{reconnect_wait} = 2;
    }

    # check the session alias
    if (!exists($opt{alias})) {
        # Debugging info...
        DEBUG && warn 'Using default Alias EasyDBI';

        # Set the default
        $opt{alias} = 'EasyDBI';
    }
    
    # check for connect error event
    if (exists($opt{connect_error})) {
        if (_ARRAY($opt{connect_error})) {
            unless ($#{$opt{connect_error}} > 0) {
                warn('connect_error must be an array reference that contains '
                .'at least a session and event, ignoring');
                delete $opt{connect_error};
            }   
        } else {
            warn('connect_error must be an array reference that contains at '
            .'least a session and event, ignoring');
            delete $opt{connect_error};
        }
    }
    
    # check for connect event
    if (exists($opt{connected})) {
        if (_ARRAY($opt{connected})) {
            unless ($#{$opt{connected}} > 0) {
                warn('connected must be an array reference that contains '
                .'at least a session and event, ignoring');
                delete $opt{connected};
            }   
        } else {
            warn('connected must be an array reference that contains at '
            .'least a session and event, ignoring');
            delete $opt{connected};
        }
    }

    if (exists($opt{options})) {
        unless (_HASH($opt{options})) {
            warn('options must be a hash ref, ignoring');
            delete $opt{options};
        }
    }

    if ($opt{stopwatch}) {
        eval "use Time::Stopwatch";
        if ($@) {
            warn('cannot use stopwatch (ignored), Time::Stopwatch not installed? ');
            delete $opt{stopwatch};
        }
    }
    
    my $keep = { map { $_ => delete $opt{$_} } @valid };

    # Anything left over is unrecognized
    if (keys %opt) {
        croak('Unrecognized keys/options ('.join(',',(keys %opt))
            .') were present in new() call to POE::Component::EasyDBI!');
    }
    
    my $self = bless($keep,$class);

    # Create a new session for ourself
    my $session = POE::Session->create(
        # Our subroutines
        'object_states' =>  [
            $self => {
                # Maintenance events
                '_start'        =>  'start',
                '_stop'         =>  'stop',
                'setup_wheel'   =>  'setup_wheel',
                'shutdown'      =>  'shutdown_poco',
                'sig_child'     =>  'sig_child',
    
                # child events
                'child_error'   =>  'child_error',
                'child_closed'  =>  'child_closed',
                'child_STDOUT'  =>  'child_STDOUT',
                'child_STDERR'  =>  'child_STDERR',
   
                'combo'         =>  'combo',
   
                # database events
                (map { $_ => 'db_handler', uc($_) => 'db_handler' } keys %COMMANDS),
            
                # Queue handling
                'send_query'    =>  'send_query',
                'check_queue'   =>  'check_queue',
            },
        ],

        # Set up the heap for ourself
        'heap'  =>  {
            # The queue of DBI calls
            'queue'         =>  [],
            'idcounter'     =>  0,

            # The Wheel::Run object placeholder
            'wheel'         =>  undef,

            # How many times have we re-created the wheel?
            'retries'       =>  0,

            # Are we shutting down?
            'shutdown'      =>  0,

            # Valid options
            'opts'          => $keep,

            # The alia/s we will run under
            'alias'         =>  $keep->{alias},

            # Number of times to retry connection
            # (if connection failures option is off)
            'max_retries'   => $keep->{max_retries} || MAX_RETRIES,

            # Connection failure option
            'no_connect_failures' => $keep->{no_connect_failures} || 0,

            # extra params for actions
            action_params => {
                commit => [],
                rollback => [],
                begin_work => [],
                func => [qw( args )],
                method => [qw( function args )],
                single => [qw( separator )],
                insert => [qw( insert hash table last_insert_id complete_request )],
                array => [qw( chunked separator )],
                arrayarray => [qw( chunked )],
                keyvalhash => [qw( primary_key chunked )],
                hashhash => [qw( primary_key chunked )],
                hasharray => [qw( primary_key chunked )],
                arrayhash => [qw( chunked )],
            },
        },
    ) or die 'Unable to create a new session!';

    # save the session id
    $self->{ID} = $session->ID;
    
    return wantarray ? ($self,$session) : $self;
}

# This subroutine handles shutdown signals
sub shutdown_poco {
    my ($kernel, $heap) = @_[KERNEL,HEAP];
    
    # Check for duplicate shutdown signals
    if ($heap->{shutdown}) {
        # Okay, let's see what's going on
        if ($heap->{shutdown} == 1 && ! defined $_[ARG0]) {
            # Duplicate shutdown events
            DEBUG && warn 'Duplicate shutdown event fired!';
            return;
        } elsif ($heap->{shutdown} == 2) {
            # Tried to shutdown_NOW again...
            DEBUG && warn 'Duplicate shutdown NOW fired!';
            return;
        }
    } else {
        # Remove our alias so we can be properly terminated
        $kernel->alias_remove($heap->{alias}) if ($heap->{alias} ne '');
        # and the child
        #$kernel->sig( 'CHLD' );
    }

    # Check if we got "NOW"
    if (defined($_[ARG0]) && uc($_[ARG0]) eq 'NOW') {
        # Actually shut down!
        $heap->{shutdown} = 2;

        if ($heap->{wheel}) {
            # KILL our subprocess
            $heap->{wheel}->kill(9);
        }

        # Delete the wheel, so we have nothing to keep
        # the GC from destructing us...
        delete $heap->{wheel};

        # Go over our queue, and do some stuff
        foreach my $queue (@{$heap->{queue}}) {
            # Skip the special EXIT actions we might have put on the queue
            if ($queue->{action} eq 'EXIT') { next }

            # Post a failure event to all the queries on the Queue
            # informing them that we have been shutdown...
            $queue->{error} = 'POE::Component::EasyDBI was '
                        .'shut down forcibly!';
            $kernel->post( $queue->{session}, $queue->{event}, $queue);

            # Argh, decrement the refcount
            $kernel->refcount_decrement( $queue->{session}, 'EasyDBI' );
        }

        # Tell the kernel to kill us!
        $kernel->signal( $_[SESSION], 'KILL' );
    } else {
        # Gracefully shut down...
        $heap->{shutdown} = 1;
        
        # Put into the queue EXIT for the child
        $kernel->yield( 'send_query', {
                action          =>  'EXIT',
                sql             =>  undef,
                placeholders    =>  undef,
            }
        );
    }
}

sub combo {
    my ($kernel, $heap, $args) = @_[KERNEL,HEAP,ARG0];

    # Get the arguments
    unless (_HASH($args)) {
        croak "first parameter to combo must be a hash ref";
    }
    
    # Add some stuff to the args
    # defaults to sender, but can be specified
    unless (defined($args->{session})) {
        $args->{session} = $_[SENDER]->ID();
        DEBUG && print "setting session to $args->{session}\n";
    }
    
    $args->{action} = $_[STATE];

    if (!exists($args->{event})) {
        # Nothing much we can do except drop this quietly...
        warn "Did not receive an event argument from caller ".$_[SENDER]->ID
            ."  State: " . $_[STATE] . " Args: " . join('',%$args);
        return;
    } else {
        my $a = ref($args->{event});
        unless (!ref($a) || $a =~ m/postback/i || $a =~ m/callback/i) {
            warn "Received an malformed event argument from caller"
                ." (only postbacks, callbacks and scalar allowed) "
                .$_[SENDER]->ID." -> State: " . $_[STATE] . " Event: $args->{event}"
                ." Args: " . %$args;
            return;
        }
    }

    my @res;
    my $handle = sub {
        push(@res, shift);
        $poe_kernel->post( $args->{session} => $args->{event} => @res ) if (delete $res[ -1 ]->{__last});
    };

    foreach my $i ( 0 .. $#{ $args->{queries} } ) {
        my ($type, $arg) = %{ $args->{queries}->[ $i ] };
        $arg->{event} = $handle;
        $arg->{__last} = ( $i == $#{ $args->{queries} } );
        $kernel->call( $_[SESSION] => $type => $arg );
    }

    return;
}

# This subroutine handles queries
sub db_handler {
    my ($kernel, $heap) = @_[KERNEL,HEAP];

    # Get the arguments
    my $args;
    if (_HASH($_[ARG0])) {
        $args = $_[ARG0];
    } else {
        warn "first parameter must be a hash ref, trying to adjust. "
            ."(fix this to get rid of this message)";
        $args = { @_[ARG0 .. $#_ ] };
    }

    # fix a stupid spelling mistake
    if ($args->{seperator}) {
        $args->{separator} = $args->{seperator};
    }
    
    # Add some stuff to the args
    # defaults to sender, but can be specified
    unless (defined($args->{session})) {
        $args->{session} = $_[SENDER]->ID();
        DEBUG && print "setting session to $args->{session}\n";
    }
    
    $args->{action} = $_[STATE];

    if (!exists($args->{event})) {
        # Nothing much we can do except drop this quietly...
        warn "Did not receive an event argument from caller ".$_[SENDER]->ID
            ."  State: " . $_[STATE] . " Args: " . join(',',%$args);
        return;
    } else {
        my $a = ref($args->{event});
        unless (!ref($a) || $a =~ m/postback/i || $a =~ m/callback/i) {
            warn "Received an malformed event argument from caller"
                ." (only postbacks, callbacks and scalar allowed) "
                .$_[SENDER]->ID." -> State: " . $_[STATE] . " Event: $args->{event}"
                ." Args: " . %$args;
            return;
        }
    }

    if (!defined($args->{sql})) {
        unless ($args->{action} =~ m/^(insert|func|method|commit|rollback|begin_work)$/i) {
            $args->{error} = 'sql is not defined!';
            # Okay, send the error to the Failure Event
            $kernel->post($args->{session}, $args->{event}, $args);
            return;
        }
    } else {
        if (ref($args->{sql})) {
            $args->{error} = 'sql is not a scalar!';
            if (_CODE($args->{event})) {
                my $callback = delete $args->{event};
                $callback->($args);
            } else {
                # send the error to the Failure Event
                $kernel->post($args->{session}, $args->{event}, $args);
            }
            return;
        }
    }

    # Check for placeholders
    if (!defined($args->{placeholders})) {
        # Create our own empty placeholders
        $args->{placeholders} = [];
    } else {
        unless (_ARRAY($args->{placeholders})) {
            $args->{error} = 'placeholders is not an array!';
            if (_CODE($args->{event})) {
                my $callback = delete $args->{event};
                $callback->($args);
            } else {
                # Okay, send the error to the Failure Event
                $kernel->post($args->{session}, $args->{event}, $args);
            }
            return;
        }
    }

    # Check for primary_key on HASHHASH or ARRAYHASH queries
    if ($args->{action} eq 'HASHHASH' || $args->{action} eq 'HASHARRAY') {
        if (!defined($args->{primary_key})) {
            $args->{error} =  'primary_key is not defined! It must '
                            .'be a column name or a 1 based index of a column';
            if (_CODE($args->{event})) {
                my $callback = delete $args->{event};
                $callback->($args);
            } else {
                $kernel->post($args->{session}, $args->{event}, $args);
            }
            return;
        } else {
            $args->{error} = 'primary_key is not a scalar!';
            if (ref($args->{sql})) {
                # send the error to the Failure Event
                if (_CODE($args->{event})) {
                    my $callback = delete $args->{event};
                    $callback->($args);
                } else {
                    $kernel->post($args->{session}, $args->{event}, $args);
                }
                return;
            }
        }
    }

    # Check if we have shutdown or not
    if ($heap->{shutdown}) {
        $args->{error} = 'POE::Component::EasyDBI is shutting'
                    .' down now, requests are not accepted!';
        # Do not accept this query
        if (_CODE($args->{event})) {
            my $callback = delete $args->{event};
            $callback->($args);
        } else {
            $kernel->post($args->{session}, $args->{event}, $args);
        }
        return;
    }

    # Increment the refcount for the session that is sending us this query
    $kernel->refcount_increment($_[SENDER]->ID(), 'EasyDBI');

    # Okay, fire off this query!
    $kernel->yield('send_query', $args);
    
    return;
}

# This subroutine starts the process of sending a query
sub send_query {
    my ($kernel, $heap, $args) = @_[KERNEL,HEAP,ARG0];
    
    # Validate that we have something
    if (!defined($args) || !_HASH($args) ) {
        return;
    }

    # Add the ID to the query
    $args->{id} = $heap->{idcounter}++;

    # Add this query to the queue
    push(@{ $heap->{queue} }, $args);

    # Send the query!
    $kernel->call($_[SESSION], 'check_queue');
    
    return;
}

# This subroutine is the meat - sends queries to the subprocess
sub check_queue {
    my ($kernel, $heap) = @_[KERNEL,HEAP];
    
    # Check if the subprocess is currently active
    return unless (!$heap->{active});

    # Check if we have a query in the queue
    return unless (scalar(@{ $heap->{queue} }) > 0);
    
    # shutting down?
    return unless ($heap->{shutdown} != 2);
    
    if ($heap->{opts}{stopwatch}) {
        tie $heap->{queue}->[0]->{stopwatch}, 'Time::Stopwatch';
    }

    # Copy what we need from the top of the queue
    my %queue;
    foreach (
        qw( id sql action placeholders no_cache begin_work commit )
        ,@{$heap->{action_params}->{$heap->{queue}->[0]->{action}}}
    ) {
        next unless (defined($heap->{queue}->[0]->{$_}));
        $queue{$_} = $heap->{queue}->[0]->{$_};
    }

    # Send data only if we are not shutting down...
    # Set the child to 'active'
    $heap->{active} = 1;

    # Put it in the wheel
    $heap->{wheel}->put(\%queue);

    return;
}

# This starts the EasyDBI
sub start {
    my ($kernel, $heap) = @_[KERNEL,HEAP];
    
    # Set up the alias for ourself
    $kernel->alias_set($heap->{alias}) if ($heap->{alias} ne '');

    # Create the wheel
    $kernel->yield('setup_wheel');
    
    return;
}

# This sets up the WHEEL
sub setup_wheel {
    my ($kernel, $heap) = @_[KERNEL,HEAP];
    
    # Are we shutting down?
    if ($heap->{shutdown}) {
#       if ($heap->{wheel}) {
#           $heap->{wheel}->kill();
#       }
        # Do not re-create the wheel...
        delete $heap->{wheel};
        return;
    }

    # Check if we should set up the wheel
    if ($heap->{retries} == $heap->{max_retries}) {
        die 'POE::Component::EasyDBI tried '.$heap->{max_retries}
            .' times to create a Wheel and is giving up...';
    }

    my %prog = (
        'Program'       =>  \&POE::Component::EasyDBI::SubProcess::main,
        'ProgramArgs'   =>  [ $heap->{opts} ],
    );

    if ($heap->{opts}{alt_fork} && $^O ne 'MSWin32') {
        %prog = (
            'Program'   =>  "$^X -MPOE::Component::EasyDBI::SubProcess -I".join(' -I',@INC)
                ." -e 'POE::Component::EasyDBI::SubProcess::main(1)'",
        );
    }

    $kernel->sig( 'CHLD', 'sig_child' );

    # Set up the SubProcess we communicate with
    $heap->{wheel} = POE::Wheel::Run->new(
        # What we will run in the separate process
        %prog,

        # Kill off existing FD's unless we're running in HELL^H^H^H^HMSWin32
        'CloseOnCall'   =>  ($^O eq 'MSWin32' ? 0 : 1),

        # Redirect errors to our error routine
        'ErrorEvent'    =>  'child_error',

        # Send child died to our child routine
        'CloseEvent'    =>  'child_closed',

        # Send input from child
        'StdoutEvent'   =>  'child_STDOUT',

        # Send input from child STDERR
        'StderrEvent'   =>  'child_STDERR',

        # Set our filters
        # Communicate with child via Storable::nfreeze
        'StdinFilter'   =>  POE::Filter::Reference->new(),
        # Receive input via Storable::nfreeze
        'StdoutFilter'  =>  POE::Filter::Reference->new(),
        # Plain ol' error lines
        'StderrFilter'  =>  POE::Filter::Line->new(),
    );

    # Check for errors
    if (! defined $heap->{wheel}) {
        die 'Unable to create a new wheel!';
    } else {
        # Increment our retry count
        $heap->{retries}++;

        # Set the wheel to inactive
        $heap->{active} = 0;

        if ($heap->{opts}{alt_fork}) {
            $heap->{wheel}->put($heap->{opts});
        }

        # Check for queries
        $kernel->call($_[SESSION], 'check_queue');
    }
    
    return;
}

# Stops everything we have
sub stop {
    # nothing to see here, move along
}

# Deletes a query from the queue, if it is not active
sub delete_query {
    my ($kernel, $heap) = @_[KERNEL,HEAP];
    # ARG0 = ID
    my $id = $_[ARG0];

    # Validation
    if (!defined($id)) {
        # Debugging
        DEBUG && warn 'In Delete_Query event with no arguments!';
        return;
    }

    # Check if the id exists + not at the top of the queue :)
    if (defined($heap->{queue}->[0])) {
        if ($heap->{queue}->[0]->{id} eq $id) {
            # Query is still active, nothing we can do...
            return undef;
        } else {
            # Search through the rest of the queue and see what we get
            foreach my $count (@{ $heap->{queue} }) {
                if ($heap->{queue}->[$count]->{id} eq $id) {
                    # Found a match, delete it!
                    splice(@{ $heap->{queue} }, $count, 1);

                    # Return success
                    return 1;
                }
            }
        }
    }

    # If we got here, we didn't find anything
    return undef;
}

# Handles child DIE'ing
sub child_closed {
    my ($kernel, $heap) = @_[KERNEL,HEAP];
    
    DEBUG && warn 'POE::Component::EasyDBI\'s Wheel closed';
    if ($heap->{shutdown}) {
#       if ($heap->{wheel}) {
#           $heap->{wheel}->kill();
#       }
        delete $heap->{wheel};
        return;
    }

    # Emit debugging information
    DEBUG && warn 'Restarting it...';

    # Create the wheel again
    delete $heap->{wheel};
    $kernel->call($_[SESSION], 'setup_wheel');
    
    return;
}

# Handles child error
sub child_error {
    my $heap = $_[HEAP];
    
    # Emit warnings only if debug is on
    DEBUG && do {
        # Copied from POE::Wheel::Run manpage
        my ($operation, $errnum, $errstr) = @_[ARG0 .. ARG2];
        warn "POE::Component::EasyDBI got an $operation error $errnum from Subprocess: '$errstr' shutdown: $heap->{shutdown}\n";
    };
    
    if ($heap->{shutdown}) {
        if ($heap->{wheel}) {
            $heap->{wheel}->kill();
        }
        delete $heap->{wheel};
        return;
    }
}

# Handles child STDOUT output
sub child_STDOUT {
    my ($kernel, $heap, $data) = @_[KERNEL,HEAP,ARG0];
    
    # Validate the argument
    unless ( _HASH($data) ) {
        warn "POE::Component::EasyDBI did not get a hash from the child ( $data )";
        return;
    }


#   DEBUG && do {
#       require Data::Dumper;
#       print Data::Dumper->Dump([$data,$heap->{queue}[0]]);
#   };

    # Check for special DB messages with ID of 'DBI'
    if ($data->{id} eq 'DBI') {
        # Okay, we received a DBI error -> error in connection...

        if ($heap->{no_connect_failures}) {
            my $qc = {};
            if (defined($heap->{queue}->[0])) {
                $qc = $heap->{queue}[0];
            }
            $qc->{error} = $data->{error};
            if (_ARRAY($heap->{opts}{connect_error})) {
                $kernel->post(@{$heap->{opts}{connect_error}}, $qc);
            } elsif ($qc->{session} && $qc->{event}) {
                if (_CODE($qc)) {
                    my $callback = delete $qc->{event};
                    $callback->($qc);
                } else {
                    $kernel->post($qc->{session}, $qc->{event}, $qc);
                }
            } else {
                warn "No connect_error defined and no queries in the queue while "
                ."error occurred: $data->{error}";
            }
#           print "DBI error: $data->{error}, retrying\n";
            return;
        }
        
        # Shutdown ourself!
        $kernel->call($_[SESSION], 'shutdown', 'NOW');

        # Too bad that we have to die...
        croak("Could not connect to DBI or database went away: $data->{error}");
    }

    if ($data->{id} eq 'DBI-CONNECTED') {
        if (_ARRAY($heap->{opts}{connected})) {
            my $query_copy = {};
            if (defined($heap->{queue}->[0])) {
                $query_copy = { %{$heap->{queue}[0]} };
            }
            $kernel->post(@{$heap->{opts}{connected}}, $query_copy);
        }
        return;
    }

    my $query;
    my $refcount_decrement = 0;
    
    if (exists($data->{chunked})) {
        # Get the query from the queue
        if (exists($data->{last_chunk})) {
            # last chunk, delete it out of the queue
            $query = shift(@{ $heap->{queue} });
            $refcount_decrement = 1;
        } else {
            $query = $heap->{queue}->[0];
        }
    } else {
        # Check to see if the ID matches with the top of the queue
        if ($data->{id} ne $heap->{queue}->[0]->{id}) {
            die "Internal error in queue/child consistency! ( CHILD: $data->{id} "
            ."QUEUE: $heap->{queue}->[0]->{id} )";
        }
        # Get the query from the top of the queue
        $query = shift(@{ $heap->{queue} });
        $refcount_decrement = 1;
    }

    # copy the query data, so we don't clobber the
    # stored data when using chunks
    #my $query_copy = { %{ $query } };
    
    # marry data from the child to the data from the queue
    #%$query_copy = (%$query_copy, %$data);
    
#   my $query_copy = { (%$query, %$data) };
    
#   my $query_copy = $query;
#   foreach (keys %$data) { $query_copy->{$_} = $data->{$_}; }

    my $query_copy = { %$query, %$data };
    
#    $poe_kernel->call( debug_logger => _log => $query_copy );

#    my ($ses,$evt) = ("$query_copy->{session}", "$query_copy->{event}");
    
#    $kernel->call($ses => $evt => $query_copy);
    
    #undef $query;
    #foreach my $k (keys %$data) {
    #   $query_copy->{$k} = $data->{$k};
    #}
    
   if (_CODE($query_copy->{event})) {
       DEBUG && print "calling callback\n";
       my $callback = delete $query_copy->{event};
       $callback->($query_copy);
   } else {
       #DEBUG && print "calling event $query->{event} in session $query->{session} from our session ".$_[SESSION]->ID."\n";
       $kernel->call($query_copy->{session} => $query_copy->{event} => $query_copy);
   }

    # Decrement the refcount for the session that sent us a query
    if ($refcount_decrement) {
        $heap->{active} = 0;
        $kernel->refcount_decrement($query_copy->{session}, 'EasyDBI');

        # Now, that we have got a result, check if we need to send another query
        $kernel->call($_[SESSION], 'check_queue');
    }

    return;
}

# Handles child STDERR output
sub child_STDERR {
    my $input = $_[ARG0];

    # Skip empty lines as the POE::Filter::Line manpage says...
    if ($input eq '') { return }

    return if ($_[HEAP]->{opts}->{no_warnings} && !DEBUG);

    warn "$input\n";
}

sub sig_child {
    # nothing
}

# ----------------
# Object methods

sub ID {
    my $self = shift;
    return $self->{ID};
}

# not called directly
sub DESTROY {
    my $self = shift;
    if (ref($self) && $self->ID) {
        $poe_kernel->post($self->ID => 'shutdown' => @_);
    } else {
        return undef;
    }
}

# End of module
1;

# egg: I like Sealab 2021. This is why you can see lots of 2021 refences in my code.

#=item C<use_cancel>
#
#Optional. If set to a true value, it will install a signal handler that will
#call $sth->cancel when a INT signal is received by the sub-process.  You may
#want to read the docs on your driver to see if this is supported.

__END__

=head1 NAME

POE::Component::EasyDBI - Perl extension for asynchronous non-blocking DBI calls in POE

=head1 SYNOPSIS

    use POE qw( Component::EasyDBI );

    # Set up the DBI
    POE::Component::EasyDBI->spawn( # or new(), witch returns an obj
        alias       => 'EasyDBI',
        dsn         => 'DBI:mysql:database=foobaz;host=192.168.1.100;port=3306',
        username    => 'user',
        password    => 'pass',
        options     => {
            AutoCommit => 0,
        },
    );

    # Create our own session to communicate with EasyDBI
    POE::Session->create(
        inline_states => {
            _start => sub {
                $_[KERNEL]->post( 'EasyDBI',
                    do => {
                        sql => 'CREATE TABLE users (id INT, username VARCHAR(100)',
                        event => 'table_created',
                    }
                );
            },
            table_created => sub {
                $_[KERNEL]->post( 'EasyDBI',
                    insert => {
                        # multiple inserts
                        insert => [
                            { id => 1, username => 'foo' },
                            { id => 2, username => 'bar' },
                            { id => 3, username => 'baz' },
                        ],
                    },
                );
                $_[KERNEL]->post( 'EasyDBI' => 'commit' );
                $_[KERNEL]->post( 'EasyDBI' => 'shutdown' );
            },
        },
    );

    $poe_kernel->run();

=head1 ABSTRACT

    This module simplifies DBI usage in POE's multitasking world.

    This module is easy to use, you'll have DBI calls in your POE program
    up and running in no time.

    It also works in Windows environments!

=head1 DESCRIPTION

This module works by creating a new session, then spawning a child process
to do the DBI queries. That way, your main POE process can continue servicing
other clients.

The standard way to use this module is to do this:

    use POE;
    use POE::Component::EasyDBI;

    POE::Component::EasyDBI->spawn( ... );

    POE::Session->create( ... );

    POE::Kernel->run();

=head2 Starting EasyDBI

To start EasyDBI, just call it's spawn method. (or new for an obj)

This one is for Postgresql:

    POE::Component::EasyDBI->spawn(
        alias       => 'EasyDBI',
        dsn         => 'DBI:Pg:dbname=test;host=10.0.1.20',
        username    => 'user',
        password    => 'pass',
    );

This one is for mysql:

    POE::Component::EasyDBI->spawn(
        alias       => 'EasyDBI',
        dsn         => 'DBI:mysql:database=foobaz;host=192.168.1.100;port=3306',
        username    => 'user',
        password    => 'pass',
    );

This method will die on error or return success.

Note the difference between dbname and database, that is dependant on the 
driver used, NOT EasyDBI

NOTE: If the SubProcess could not connect to the DB, it will return an error,
causing EasyDBI to croak/die.

NOTE: Starting with version .10, I've changed new() to return a EasyDBI object
and spawn() returns a session reference.  Also, create() is the same as spawn().
See L<OBJECT METHODS>.

This constructor accepts 6 different options.

=over 4

=item C<alias>

This will set the alias EasyDBI uses in the POE Kernel.
This will default TO "EasyDBI" if undef

If you do not want to use aliases, specify '' as the ailas.  This helps when
spawning many EasyDBI objects. See L<OBJECT METHODS>.

=item C<dsn>

This is the DSN (Database connection string)

EasyDBI expects this to contain everything you need to connect to a database
via DBI, without the username and password.

For valid DSN strings, contact your DBI driver's manual.

=item C<username>

This is the DB username EasyDBI will use when making the call to connect

=item C<password>

This is the DB password EasyDBI will use when making the call to connect

=item C<options>

Pass a hash ref that normally would be after the $password param on a 
DBI->connect call.

=item C<max_retries>

This is the max number of times the database wheel will be restarted, default
is 5.  Set this to -1 to retry forever.

=item C<ping_timeout>

Optional. This is the timeout to ping the database handle.  If set to 0 the
database will be pinged before every query.  The default is 0.

=item C<no_connect_failures>

Optional. If set to a true value, the connect_error event will be valid, but not
necessary.  If set to a false value, then connection errors will be fatal.

=item C<connect_error>

Optional. Supply a array ref of session_id or alias and an event.  Any connect
errors will be posted to this session and event with the query that failed as
ARG0 or an empty hash ref if no query was in the queue.  The query will be
retried, so DON'T resend the query.  If this parameter is not supplied, the
normal behavour will be to drop the subprocess and restart L<max_retries> times.

=item C<reconnect_wait>

Optional. Defaults to 2 seconds. After a connection failure this is the time
to wait until another connection is attempted.  Setting this to 0 would not
be good for your cpu load.

=item C<connected>

Optional. Supply a array ref of session_id or alias and an event.  When
the component makes a successful connection this event will be called
with the next query as ARG0 or an empty hash ref if no queries are in the queue.
DON'T resend the query, it will be processed.

=item C<no_cache>

Optional. If true, prepare_cached won't be called on queries.  Use this when
using L<DBD::AnyData>.  This can be overridden with each query.

=item C<alt_fork>

Optional. If true, an alternate type of fork will be used for the database
process.  This usually results in lower memory use of the child.
*Experimental, and WILL NOT work on Windows Platforms*

=item C<stopwatch>

Optional. If true, L<Time::Stopwatch> will be loaded and tied to the 'stopwatch'
key on every query.  Check the stopwatch key in the return event to measure how
long a query took.

=back

=head2 Events

There is only a few events you can trigger in EasyDBI.
They all share a common argument format, except for the shutdown event.


Note: you can change the session that the query posts back to, it uses $_[SENDER]
as the default.

You can use a postback, or callback (See POE::Session)

For example:

    $kernel->post( 'EasyDBI',
        quote => {
                sql => 'foo$*@%%sdkf"""',
                event => 'quoted_handler',
                session => 'dbi_helper', # or session id
        }
    );

or

    $kernel->post( 'EasyDBI',
        quote => {
                sql => 'foo$*@%%sdkf"""',
                event => $_[SESSION]->postack("quoted_handler"),
                session => 'dbi_helper', # or session id
        }
    );


=over 4

=item C<quote>

    This sends off a string to be quoted, and gets it back.

    Internally, it does this:

    return $dbh->quote( $SQL );

    Here's an example on how to trigger this event:

    $kernel->post( 'EasyDBI',
        quote => {
            sql => 'foo$*@%%sdkf"""',
            event => 'quoted_handler',
        }
    );

    The Success Event handler will get a hash ref in ARG0:
    {
        sql     =>  Unquoted SQL sent
        result  =>  Quoted SQL
    }

=item C<do>

    This query is for those queries where you UPDATE/DELETE/etc.

    Internally, it does this:

    $sth = $dbh->prepare_cached( $sql );
    $rows_affected = $sth->execute( $placeholders );
    return $rows_affected;

    Here's an example on how to trigger this event:

    $kernel->post( 'EasyDBI',
        do => {
            sql => 'DELETE FROM FooTable WHERE ID = ?',
            placeholders => [ qw( 38 ) ],
            event => 'deleted_handler',
        }
    );

    The Success Event handler will get a hash in ARG0:
    {
        sql             =>  SQL sent
        result          =>  Scalar value of rows affected
        rows            =>  Same as result
        placeholders    =>  Original placeholders
    }

=item C<single>

    This query is for those queries where you will get exactly one row and
    column back.

    Internally, it does this:

    $sth = $dbh->prepare_cached( $sql );
    $sth->bind_columns( %result );
    $sth->execute( $placeholders );
    $sth->fetch();
    return %result;

    Here's an example on how to trigger this event:

    $kernel->post( 'EasyDBI',
        single => {
            sql => 'Select test_id from FooTable',
            event => 'result_handler',
        }
    );

    The Success Event handler will get a hash in ARG0:
    {
        sql             =>  SQL sent
        result          =>  scalar
        placeholders    =>  Original placeholders
    }

=item C<arrayhash>

    This query is for those queries where you will get more than one row and
    column back. Also see arrayarray

    Internally, it does this:

    $sth = $dbh->prepare_cached( $SQL );
    $sth->execute( $PLACEHOLDERS );
    while ( $row = $sth->fetchrow_hashref() ) {
        push( @results,{ %{ $row } } );
    }
    return @results;

    Here's an example on how to trigger this event:

    $kernel->post( 'EasyDBI',
        arrayhash => {
            sql => 'SELECT this, that FROM my_table WHERE my_id = ?',
            event => 'result_handler',
            placeholders => [ qw( 2021 ) ],
        }
    );

    The Success Event handler will get a hash in ARG0:
    {
        sql             =>  SQL sent
        result          =>  Array of hashes of the rows ( array of fetchrow_hashref's )
        rows            =>  Scalar value of rows
        placeholders    =>  Original placeholders
        cols            =>  An array of the cols in query order
    }

=item C<hashhash>

    This query is for those queries where you will get more than one row and
    column back.

    The primary_key should be UNIQUE! If it is not, then use hasharray instead.

    Internally, it does something like this:

    if ($primary_key =~ m/^\d+$/) {
        if ($primary_key} > $sth->{NUM_OF_FIELDS}) {
            die "primary_key is out of bounds";
        }
        $primary_key = $sth->{NAME}->[($primary_key-1)];
    }
    
    for $i ( 0 .. $sth->{NUM_OF_FIELDS}-1 ) {
        $col{$sth->{NAME}->[$i]} = $i;
        push(@cols, $sth->{NAME}->[$i]);
    }

    $sth = $dbh->prepare_cached( $SQL );
    $sth->execute( $PLACEHOLDERS );
    while ( @row = $sth->fetch_array() ) {
        foreach $c (@cols) {
            $results{$row[$col{$primary_key}]}{$c} = $row[$col{$c}];
        }
    }
    return %results;

    Here's an example on how to trigger this event:

    $kernel->post( 'EasyDBI',
        hashhash => {
            sql => 'SELECT this, that FROM my_table WHERE my_id = ?',
            event => 'result_handler',
            placeholders => [ qw( 2021 ) ],
            primary_key => "2",  # making 'that' the primary key
        }
    );

    The Success Event handler will get a hash in ARG0:
    {
        sql             =>  SQL sent
        result          =>  Hashes of hashes of the rows
        rows            =>  Scalar value of rows
        placeholders    =>  Original placeholders
        cols            =>  An array of the cols in query order
    }

=item C<hasharray>

    This query is for those queries where you will get more than one row 
    and column back.

    Internally, it does something like this:

    # find the primary key
    if ($primary_key =~ m/^\d+$/) {
        if ($primary_key} > $sth->{NUM_OF_FIELDS}) {
            die "primary_key is out of bounds";
        }
        $primary_key = $sth->{NAME}->[($primary_key-1)];
    }
    
    for $i ( 0 .. $sth->{NUM_OF_FIELDS}-1 ) {
        $col{$sth->{NAME}->[$i]} = $i;
        push(@cols, $sth->{NAME}->[$i]);
    }
    
    $sth = $dbh->prepare_cached( $SQL );
    $sth->execute( $PLACEHOLDERS );
    while ( @row = $sth->fetch_array() ) {
        push(@{ $results{$row[$col{$primary_key}}]} }, @row);
    }
    return %results;

    Here's an example on how to trigger this event:

    $kernel->post( 'EasyDBI',
        hasharray => {
            sql => 'SELECT this, that FROM my_table WHERE my_id = ?',
            event => 'result_handler',
            placeholders => [ qw( 2021 ) ],
            primary_key => "1",  # making 'this' the primary key
        }
    );

    The Success Event handler will get a hash in ARG0:
    {
        sql             =>  SQL sent
        result          =>  Hashes of hashes of the rows
        rows            =>  Scalar value of rows
        placeholders    =>  Original placeholders
        primary_key     =>  'this' # the column name for the number passed in
        cols            =>  An array of the cols in query order
    }

=item C<array>

    This query is for those queries where you will get more than one row with
    one column back. (or joined columns)

    Internally, it does this:

    $sth = $dbh->prepare_cached( $SQL );
    $sth->execute( $PLACEHOLDERS );
    while ( @row = $sth->fetchrow_array() ) {
        if ($separator) {
            push( @results,join($separator,@row) );
        } else {
            push( @results,join(',',@row) );
        }
    }
    return @results;

    Here's an example on how to trigger this event:

    $kernel->post( 'EasyDBI',
        array => {
            sql => 'SELECT this FROM my_table WHERE my_id = ?',
            event => 'result_handler',
            placeholders => [ qw( 2021 ) ],
            separator => ',', # default separator
        }
    );

    The Success Event handler will get a hash in ARG0:
    {
        sql             =>  SQL sent
        result          =>  Array of scalars (joined with separator if more
            than one column is returned)
        rows            =>  Scalar value of rows
        placeholders    =>  Original placeholders
    }

=item C<arrayarray>

    This query is for those queries where you will get more than one row and
    column back. Also see arrayhash

    Internally, it does this:

    $sth = $dbh->prepare_cached( $SQL );
    $sth->execute( $PLACEHOLDERS );
    while ( @row = $sth->fetchrow_array() ) {
        push( @results,\@row );
    }
    return @results;

    Here's an example on how to trigger this event:

    $kernel->post( 'EasyDBI',
        arrayarray => {
            sql => 'SELECT this,that FROM my_table WHERE my_id > ?',
            event => 'result_handler',
            placeholders => [ qw( 2021 ) ],
        }
    );

    The Success Event handler will get a hash in ARG0:
    {
        sql             =>  SQL sent
        result          =>  Array of array refs
        rows            =>  Scalar value of rows
        placeholders    =>  Original placeholders
    }


=item C<hash>

    This query is for those queries where you will get one row with more than
    one column back.

    Internally, it does this:

    $sth = $dbh->prepare_cached( $SQL );
    $sth->execute( $PLACEHOLDERS );
    @row = $sth->fetchrow_array();
    if (@row) {
        for $i ( 0 .. $sth->{NUM_OF_FIELDS}-1 ) {
            $results{$sth->{NAME}->[$i]} = $row[$i];
        }
    }
    return %results;

    Here's an example on how to trigger this event:

    $kernel->post( 'EasyDBI',
        hash => {
            sql => 'SELECT * FROM my_table WHERE my_id = ?',
            event => 'result_handler',
            placeholders => [ qw( 2021 ) ],
        }
    );

    The Success Event handler will get a hash in ARG0:
    {
        sql             =>  SQL sent
        result          =>  Hash
        rows            =>  Scalar value of rows
        placeholders    =>  Original placeholders
    }

=item C<keyvalhash>

    This query is for those queries where you will get one row with more than
    one column back.

    Internally, it does this:

    $sth = $dbh->prepare_cached( $SQL );
    $sth->execute( $PLACEHOLDERS );
    while ( @row = $sth->fetchrow_array() ) {
        $results{$row[0]} = $row[1];
    }
    return %results;

    Here's an example on how to trigger this event:

    $kernel->post( 'EasyDBI',
        keyvalhash => {
            sql => 'SELECT this, that FROM my_table WHERE my_id = ?',
            event => 'result_handler',
            placeholders => [ qw( 2021 ) ],
            primary_key => 1, # uses 'this' as the key
        }
    );

    The Success Event handler will get a hash in ARG0:
    {
        sql             =>  SQL sent
        result          =>  Hash
        rows            =>  Scalar value of rows
        placeholders    =>  Original placeholders
    }

=item C<insert>

    This is for inserting rows.

    Here's an example on how to trigger this event:

    $_[KERNEL]->post( 'EasyDBI',
        insert => {
            sql => 'INSERT INTO zipcodes (zip,city,state) VALUES (?,?,?)',
            placeholders => [ '98004', 'Bellevue', 'WA' ],
            event => 'insert_handler',
        }
    );

    also an example to retrieve a last insert id

    $_[KERNEL]->post( 'EasyDBI',
        insert => {
            hash => { username => 'test' , pass => 'sUpErSeCrEt', name => 'John' },
            table => 'users',
            last_insert_id => {
                field => 'user_id', # mysql uses SELECT LAST_INSERT_ID instead
                table => 'users',   # of these values, just specify {} for mysql
            },
            # or last_insert_id can be => 'SELECT LAST_INSERT_ID()' or some other
            # query that will return a value
        },
    );

    The Success Event handler will get a hash in ARG0:
    {
        sql             =>  SQL sent
        table           =>  Table from insert
        placeholders    =>  Original placeholders
        last_insert_id  =>  The original hash or scalar sent
        insert_id       =>  Insert id if last_insert_id is used
        rows            =>  Number of rows affected
        result          =>  Same as rows
    }

=item C<func>

    This is for calling $dbh->func(), when using a driver that supports it.  
    
    Internally, it does this:

    return $dbh->func(@{$args});

    Here's an example on how to trigger this event (Using DBD::AnyData):

    $kernel->post( 'EasyDBI',
        func => {
            args => ['test2','CSV',["id,phrase\n1,foo\n2,bar"],'ad_import'],
            event => 'result_handler',
        }
    );

    The Success Event handler will get a hash in ARG0:
    {
        sql             =>  SQL sent
        result          =>  return value
    }

=item C<commit>

    This is for calling $dbh->commit(), if the driver supports it.
    
    Internally, it does this:

    return $dbh->commit();

    Here's an example on how to trigger this event:

    $kernel->post( 'EasyDBI',
        commit => {
            event => 'result_handler',
        }
    );

    The Success Event handler will get a hash in ARG0:
    {
        sql             =>  SQL sent
        result          =>  return value
    }

=item C<rollback>

    This is for calling $dbh->rollback(), if the driver supports it.
    
    Internally, it does this:

    return $dbh->rollback();

    Here's an example on how to trigger this event:

    $kernel->post( 'EasyDBI',
        rollback => {
            event => 'result_handler',
        }
    );

    The Success Event handler will get a hash in ARG0:
    {
        sql             =>  SQL sent
        result          =>  return value
    }

=item C<begin_work>

    This is for calling $dbh->begin_work(), if the driver supports it.
    
    Internally, it does this:

    return $dbh->begin_work();

    Here's an example on how to trigger this event:

    $kernel->post( 'EasyDBI',
        begin_work => {
            event => 'result_handler',
        }
    );

    The Success Event handler will get a hash in ARG0:
    {
        sql             =>  SQL sent
        result          =>  return value
    }

=item C<shutdown>

    $kernel->post( 'EasyDBI', 'shutdown' );

    This will signal EasyDBI to start the shutdown procedure.

    NOTE: This will let all outstanding queries run!
    EasyDBI will kill it's session when all the queries have been processed.

    you can also specify an argument:

    $kernel->post( 'EasyDBI', 'shutdown' => 'NOW' );

    This will signal EasyDBI to shutdown.

    NOTE: This will NOT let the outstanding queries finish!
    Any queries running will be lost!

    Due to the way POE's queue works, this shutdown event will take some time
    to propagate POE's queue. If you REALLY want to shut down immediately, do
    this:

    $kernel->call( 'EasyDBI', 'shutdown' => 'NOW' );

    ALL shutdown NOW's send kill 9 to thier children, beware of any 
    transactions that you may be in. Your queries will revert if you are in
    transaction mode

=back

=head3 Arguments

They are passed in via the $kernel->post( ... );

Note: all query types can be in ALL-CAPS or lowercase but not MiXeD!

ie ARRAYHASH or arrayhash but not ArrayHash


=over 4

=item C<sql>

This is the actual SQL line you want EasyDBI to execute.
You can put in placeholders, this module supports them.

=item C<placeholders>

This is an array of placeholders.

You can skip this if your query does not use placeholders in it.

=item C<event>

This is the success/failure event, triggered whenever a query finished
successfully or not.

It will get a hash in ARG0, consult the specific queries on what you will get.

***** NOTE *****

In the case of an error, the key 'error' will have the specific error that
occurred.  Always, always, _always_ check for this in this event.

***** NOTE *****

=item C<separator>

Query types single, and array accept this parameter.
The default is a comma (,) and is optional

If a query has more than one column returned, the columns are joined with
the 'separator'.

=item C<primary_key>

Query types hashhash, and hasharray accept this parameter.
It is used to key the hash on a certain field

=item C<chunked>

All multi-row queries can be chunked.

You can pass the parameter 'chunked' with a number of rows to fire the 'event'
event for every 'chunked' rows, it will fire the 'event' event. (a 'chunked'
key will exist) A 'last_chunk' key will exist when you have received the last
chunk of data from the query

=item C<last_insert_id>

See the insert event for a example of its use.

=item C<begin_work>

Optional.  Works with all queries.  You should have AutoCommit => 0 set on
connect.

=item C<commit>

Optional.  After a successful 'do' or 'insert', a commit is performed.
ONLY used when using C<do> or C<insert>

=item (arbitrary data)

You can pass custom keys and data not mentioned above, BUT I suggest using a
prefix like _ in front of your custom keys.  For example:

    $_[KERNEL->post( 'EasyDBI',
        do => {
            sql => 'DELETE FROM sessions WHERE ip = ?',
            placeholders => [ $ip ],
            _ip => $ip,
            _port => $port,
            _filehandle => $fh,
        }
    );

If I were to add an option 'filehandle' (for importing data from a file for 
instance) you don't want an upgrade to produce BAD results.

=back

=head2 OBJECT METHODS

When using new() to spawn/create the EasyDBI object, you can use the methods
listed below

NOTE: The object interface will be improved in later versions, please send
suggestions to the author.

=over 4

=item C<ID>

This retrieves the session ID.  When managing a pool of EasyDBI objects, you
can set the alias to '' (nothing) and retrieve the session ID in this manner.

    $self->ID()

=item C<DESTROY>

This will shutdown EasyDBI.

    $self->DESTROY()

=back

=head2 LONG EXAMPLE

    use POE qw( Component::EasyDBI );

    # Set up the DBI
    POE::Component::EasyDBI->spawn(
        alias       => 'EasyDBI',
        dsn         => 'DBI:mysql:database=foobaz;host=192.168.1.100;port=3306',
        username    => 'user',
        password    => 'pass',
    );
    
    # Create our own session to communicate with EasyDBI
    POE::Session->create(
        inline_states => {
            _start => sub {
                $_[KERNEL]->post( 'EasyDBI',
                    do => {
                        sql => 'DELETE FROM users WHERE user_id = ?',
                        placeholders => [ qw( 144 ) ],
                        event => 'deleted_handler',
                    }
                );

                # 'single' is very different from the single query in SimpleDBI
                # look at 'hash' to get those results
                
                # If you select more than one field, you will only get the last one
                # unless you pass in a separator with what you want the fields seperated by
                # to get null sperated values, pass in separator => "\0"
                $_[KERNEL]->post( 'EasyDBI',
                    single => {
                        sql => 'Select user_id,user_login from users where user_id = ?',
                        event => 'single_handler',
                        placeholders => [ qw( 144 ) ],
                        separator => ',', #optional!
                    }
                );

                $_[KERNEL]->post( 'EasyDBI',
                    quote => {
                        sql => 'foo$*@%%sdkf"""',
                        event => 'quote_handler',
                    }
                );
                
                $_[KERNEL]->post( 'EasyDBI',
                    arrayhash => {
                        sql => 'SELECT user_id,user_login from users where logins = ?',
                        event => 'arrayash_handler',
                        placeholders => [ qw( 53 ) ],
                    }
                );
                
                my $postback = $_[SESSION]->postback("arrayhash_handler",3,2,1);
                
                $_[KERNEL]->post( 'EasyDBI',
                    arrayhash => {
                        sql => 'SELECT user_id,user_login from users',
                        event => $postback,
                    }
                );
                
                $_[KERNEL]->post( 'EasyDBI',
                    arrayarray => {
                        sql => 'SELECT * from locations',
                        event => 'arrayarray_handler',
                        primary_key => '1', # you can specify a primary key, or a number based on what column to use
                    }
                );

                $_[KERNEL]->post( 'EasyDBI',
                    hashhash => {
                        sql => 'SELECT * from locations',
                        event => 'hashhash_handler',
                        primary_key => '1', # you can specify a primary key, or a number based on what column to use
                    }
                );
                
                $_[KERNEL]->post( 'EasyDBI',
                    hasharray => {
                        sql => 'SELECT * from locations',
                        event => 'hasharray_handler',
                        primary_key => "1",
                    }
                );
                
                # you should use limit 1, it is NOT automaticly added
                $_[KERNEL]->post( 'EasyDBI',
                    hash => {
                        sql => 'SELECT * from locations LIMIT 1',
                        event => 'hash_handler',
                    }
                );
                
                $_[KERNEL]->post( 'EasyDBI',
                    array => {
                        sql => 'SELECT location_id from locations',
                        event => 'array_handler',
                    }
                );
                
                $_[KERNEL]->post( 'EasyDBI',
                    keyvalhash => {
                        sql => 'SELECT location_id,location_name from locations',
                        event => 'keyvalhash_handler',
                        # if primary_key isn't used, the first one is assumed
                    }
                );

                $_[KERNEL]->post( 'EasyDBI',
                    insert => {
                        sql => 'INSERT INTO zipcodes (zip,city,state) VALUES (?,?,?)',
                        placeholders => [ '98004', 'Bellevue', 'WA' ],
                        event => 'insert_handler',
                    }
                );

                $_[KERNEL]->post( 'EasyDBI',
                    insert => {
                        # this can also be a array of hashes similar to this
                        hash => { username => 'test' , pass => 'sUpErSeCrEt', name => 'John' },
                        table => 'users',
                        last_insert_id => {
                            field => 'user_id', # mysql uses SELECT LAST_INSERT_ID instead
                            table => 'users',   # of these values, just specify {} for mysql
                        },
                        event => 'insert_handler',
                        # or last_insert_id can be => 'SELECT LAST_INSERT_ID()' or some other
                        # query that will return a value
                    },
                );
                
                # 3 ways to shutdown

                # This will let the existing queries finish, then shutdown
                $_[KERNEL]->post( 'EasyDBI', 'shutdown' );

                # This will terminate when the event traverses
                # POE's queue and arrives at EasyDBI
                #$_[KERNEL]->post( 'EasyDBI', shutdown => 'NOW' );

                # Even QUICKER shutdown :)
                #$_[KERNEL]->call( 'EasyDBI', shutdown => 'NOW' );
            },

            deleted_handler => \&deleted_handler,
            quote_handler   => \&quote_handler,
            arrayhash_handler => \&arrayhash_handler,
        },
    );

    sub quote_handler {
        # For QUOTE calls, we receive the scalar string of SQL quoted
        # $_[ARG0] = {
        #   sql => The SQL you sent
        #   result  => scalar quoted SQL
        #   placeholders => The placeholders
        #   action => 'QUOTE'
        #   error => Error occurred, check this first
        # }
    }

    sub deleted_handler {
        # For DO calls, we receive the scalar value of rows affected
        # $_[ARG0] = {
        #   sql => The SQL you sent
        #   result  => scalar value of rows affected
        #   placeholders => The placeholders
        #   action => 'do'
        #   error => Error occurred, check this first
        # }
    }

    sub single_handler {
        # For SINGLE calls, we receive a scalar
        # $_[ARG0] = {
        #   SQL => The SQL you sent
        #   result  => scalar
        #   placeholders => The placeholders
        #   action => 'single'
        #   separator => Seperator you may have sent
        #   error => Error occurred, check this first
        # }
    }

    sub arrayhash_handler {
        # For arrayhash calls, we receive an array of hashes
        # $_[ARG0] = {
        #   sql => The SQL you sent
        #   result  => array of hash refs
        #   placeholders => The placeholders
        #   action => 'arrayhash'
        #   error => Error occurred, check this first
        # }
    }

    sub hashhash_handler {
        # For hashhash calls, we receive a hash of hashes
        # $_[ARG0] = {
        #   sql => The SQL you sent
        #   result  => hash ref of hash refs keyed on primary key
        #   placeholders => The placeholders
        #   action => 'hashhash'
        #   cols => array of columns in order (to help recreate the sql order)
        #   primary_key => column you specified as primary key, if you specifed
        #       a number, the real column name will be here
        #   error => Error occurred, check this first
        # }
    }

    sub hasharray_handler {
        # For hasharray calls, we receive an hash of arrays
        # $_[ARG0] = {
        #   sql => The SQL you sent
        #   result  => hash ref of array refs keyed on primary key
        #   placeholders => The placeholders
        #   action => 'hashhash'
        #   cols => array of columns in order (to help recreate the sql order)
        #   primary_key => column you specified as primary key, if you specifed
        #           a number, the real column name will be here
        #   error => Error occurred, check this first
        # }
    }
    
    sub array_handler {
        # For array calls, we receive an array
        # $_[ARG0] = {
        #   sql => The SQL you sent
        #   result  => an array, if multiple fields are used, they are comma
        #           seperated (specify separator in event call to change this)
        #   placeholders => The placeholders
        #   action => 'array'
        #   separator => you sent  # optional!
        #   error => Error occurred, check this first
        # }
    }
    
    sub arrayarray_handler {
        # For array calls, we receive an array ref of array refs
        # $_[ARG0] = {
        #   sql => The SQL you sent
        #   result  => an array ref of array refs
        #   placeholders => The placeholders
        #   action => 'arrayarray'
        #   error => Error occurred, check this first
        # }
    }
    
    sub hash_handler {
        # For hash calls, we receive a hash
        # $_[ARG0] = {
        #   sql => The SQL you sent
        #   result  => a hash
        #   placeholders => The placeholders
        #   action => 'hash'
        #   error => Error occurred, check this first
        # }
    }

    sub keyvalhash_handler {
        # For keyvalhash calls, we receive a hash
        # $_[ARG0] = {
        #   sql => The SQL you sent
        #   result  => a hash  # first field is the key, second is the value
        #   placeholders => The placeholders
        #   action => 'keyvalhash'
        #   error => Error occurred, check this first
        #   primary_key => primary key used
        # }
    }
    
    sub insert_handle {
        # $_[ARG0] = {
        #   sql => The SQL you sent
        #   placeholders => The placeholders
        #   action => 'insert'
        #   table => 'users',
        #   # for postgresql, or others?
        #   last_insert_id => { # used to retrieve the insert id of the inserted row
        #       field => The field of id requested
        #       table => The table the holds the field
        #   },
        #   -OR-
        #   last_insert_id => 'SELECT LAST_INSERT_ID()', # mysql style
        #   result => the id from the last_insert_id post query
        #   error => Error occurred, check this first
        # }
    }


=head2 EasyDBI Notes

This module is very picky about capitalization!

All of the options are in lowercase.  Query types can be in ALL-CAPS or lowercase.

This module will try to keep the SubProcess alive.
if it dies, it will open it again for a max of 5 retries by
default, but you can override this behavior by using L<max_retries>

=head2 EXPORT

Nothing.

=head1 SEE ALSO

L<DBI>, L<POE>, L<POE::Wheel::Run>, L<POE::Component::DBIAgent>, L<POE::Component::LaDBI>,
L<POE::Component::SimpleDBI>, L<DBD::AnyData>, L<DBD::SQLite>

=head1 AUTHOR

David Davis E<lt>xantus@cpan.orgE<gt>

=head1 CREDITS

Apocalypse E<lt>apocal@cpan.orgE<gt> for L<POE::Component::SimpleDBI>, and the
alternate fork.

Please rate this module. L<http://cpanratings.perl.org/rate/?distribution=POE-Component-EasyDBI>

=head1 COPYRIGHT AND LICENSE

Copyright 2003-2005 by David Davis and Teknikill Software

This library is free software; you can redistribute it and/or modify
it under the same terms as Perl itself.

=cut
