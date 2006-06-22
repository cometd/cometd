package POE::Component::Server::ShortBus;

use strict;
use warnings;

our $VERSION = '0.01';
our $LogLevel = 0;

use Socket;
use Carp;
use BSD::Resource;

use POE qw(
    Wheel::SocketFactory
    Driver::SysRW
    Wheel::ReadWrite
    Filter::Line
);

sub spawn {
    my $package = shift;
    croak "$package requires an even number of parameters" if @_ % 2;
    my %opts = @_;
    my $alias = $opts{Alias};
    $alias = 'shortbusd' unless defined($alias) and length($alias);
    $opts{Alias} = $alias;
    $opts{ListenPort} = $opts{ListenPort} || 6000;
    $opts{TimeOut} = defined $opts{TimeOut} ? $opts{TimeOut} : 30;
    $opts{ListenAddress} = $opts{ListenAddress} || '0.0.0.0';
    $LogLevel = delete $opts{LogLevel} || 0;

    if ($opts{MaxConnections}) {
        my $ret = setrlimit(RLIMIT_NOFILE, $opts{MaxConnections}, $opts{MaxConnections});
        unless (defined $ret && $ret) {
            if ($> == 0) {
                warn "Unable to set max connections limit";
            } else {
                warn "Need to be root to increase max connections";
            }
        }
    }

    my $self = bless( { 
        opts => \%opts, 
        heaps => {},
        connections => 0,
    }, $package );

    POE::Session->create(
#       options => { trace=>1 },
       heap => $self,
       object_states => [
            $self => [qw(
                _start
                _default
                _stop

                _status_clients

                register
                unregister
                notify
                signals

                send

                connect_to_client
                remote_connect_success
                remote_connect_timeout
                remote_connect_error
                
                remote_error
                remote_input
                remote_flush

                local_accept
                local_receive
                local_flushed
                local_error
                local_timeout
            )]
        ],
    );

    return 1;
}

sub _start {
    my ($kernel, $session, $self) = @_[KERNEL, SESSION, OBJECT];

    $session->option( @{$self->{opts}{SessionOptions}} ) if $self->{opts}{SessionOptions};
    $kernel->alias_set($self->{opts}{Alias}) if ($self->{opts}{Alias});

    # watch for SIGINT, SIGCHLD
    foreach (qw( INT CHLD )) {
        $kernel->sig($_ => 'signals');
    }

    # create a socket factory
    $self->{wheel} = POE::Wheel::SocketFactory->new(
        BindPort       => $self->{opts}{ListenPort},
        BindAddress    => $self->{opts}{ListenAddress},
        Reuse          => 'yes',
        SuccessEvent   => 'local_accept',
        FailureEvent   => 'local_error',
        ListenQueue    => 10000,
    );

    # connect to our client list
    
    if ($self->{opts}{ClientList} && ref($self->{opts}{ClientList}) eq 'ARRAY') {
        my $d = .1;
        foreach (@{$self->{opts}{ClientList}}) {
            $kernel->delay_set(connect_to_client => $d =>  $_);
            $d += .02;
        }
    }

    $self->_log(v => 2, msg => "Listening to port $self->{opts}{ListenPort} on $self->{opts}{ListenAddress}");

    $kernel->yield('_status_clients');
}

sub _stop {
    $_[OBJECT]->_log(v => 2, msg => "Server stopped.");
}

sub _status_clients {
    my $self = $_[OBJECT];
    $_[KERNEL]->delay_set( _status_clients => 5 );
    $self->_log(v => 2, msg => "in + out connections: $self->{connections}");
}

sub register {
    my($kernel, $self, $sender) = @_[KERNEL, OBJECT, SENDER];
    $kernel->refcount_increment($sender->ID, __PACKAGE__);
    $self->{listeners}->{$sender->ID} = 1;
    $kernel->post($sender->ID => sb_registered => $_[SESSION]->ID);
    #$self->_log(v => 2, msg => "Listening to port $self->{opts}{ListenPort} on $self->{opts}{ListenAddress}");
    return $_[SESSION]->ID();
}

sub unregister {
    my($kernel, $self, $sender) = @_[KERNEL, OBJECT, SENDER];
    $kernel->refcount_decrement($sender->ID, __PACKAGE__);
    delete $self->{listeners}->{$sender->ID};
}

sub notify {
    my($kernel, $self, $name, $data) = @_[KERNEL, OBJECT, ARG0, ARG1];
    
    my $ret = 0;
    foreach (keys %{$self->{listeners}}) {
        my $tmp = $kernel->call($_ => $name => $data);
        if (defined($tmp)) {
            $ret += $tmp;
        }
    }
    
    return ($ret > 0) ? 1 : 0;
}

sub send {
    my $c = $_[OBJECT]->{heaps}->{$_[ARG0]}->{con};
    if ($c) {
        $c->put($_[ARG1]);
    }
}

sub add_client_obj {
    my $self = shift;
    my $cheap = shift;
    $self->{heaps}->{"$cheap"} = $cheap;
    undef;
}

sub create_event {
    my ($self,$cheap,$event) = @_;
    "$cheap|$event";
}

sub signals {
    my ($self, $signal_name) = @_[OBJECT, ARG0];

    $self->_log(v => 1, msg => "Server caught SIG$signal_name");

    # to stop ctrl-c / INT
    if ($signal_name eq 'INT') {
        #$_[KERNEL]->sig_handled();
    }

    return 0;
}

sub _log {
    my ($self, %o) = @_;
    if ($o{v} <= $LogLevel) {
        my $sender = (defined $self->{cheap} && defined $self->{cheap}->{peer_ip})
            ? $self->{cheap}->{peer_ip} : "?";
        my $type = (defined $o{type}) ? $o{type} : 'M';
#        my $caller = (caller(1))[3] || '????';
#        my $pk = __PACKAGE__.'::';
#        $caller =~ s/$pk//;
        my $caller = '';
        print STDERR '['.localtime()."][$type][$caller][$sender] $o{msg}\n";
    }
}

sub reconnect_to_client {
    my ($self, $cheap) = @_;

    $cheap->{connected} = 0;

    delete $cheap->{sf};
    delete $cheap->{con};

    if ($self->{opts}->{ConnectTimeOut}) {
        $cheap->{timeout_id} = $poe_kernel->alarm_set(
            $self->create_event($cheap,'remote_connect_timeout') => time() + $self->{opts}->{ConnectTimeOut}
        );
    }

    $cheap->{sf} = POE::Wheel::SocketFactory->new(
        RemoteAddress => $cheap->{peer_ip},
        RemotePort    => $cheap->{peer_port},
        SuccessEvent  => $self->create_event($cheap,'remote_connect_success'),
        FailureEvent  => $self->create_event($cheap,'remote_connect_error'),
    );
    
}

sub cleanup_client {
    my ($self, $cheap) = @_;

    $self->{connections}--;

    delete $self->{heaps}->{"$cheap"};

    undef;
}


sub _default {
    my ($self, $cmd, $args) = @_[OBJECT, ARG0, ARG1];

    if ($cmd !~ /^_/ && $cmd =~ m/^([^\|]+)\|(.*)/) {
        
        return unless ($1 && $2);

#        $self->_log(v => 5, msg => "dispatching $2 for $1");

        unless (defined(&$2)) {
            $self->_log(v => 1, msg => "subroutine $2 does not exist");
            warn "subroutine $2 does not exist";
            return 0;
        }
        
        if ($self->{heaps}->{$1}) {
            $self->{cheap} = $self->{heaps}->{$1};
        
            splice(@_, ARG0, $#_, @$args);
       
            no strict 'refs';
            goto &$2;

            $self->{cheap} = undef;
        } else {
            warn "NO HEAP FOR EVENT $2 WITH REF $1";
        }
    }
    
    return 0;
}

sub connect_to_client {
    my ($kernel, $self, $addr) = @_[KERNEL, OBJECT, ARG0];

    my ($address, $port) = ($addr =~ /^([^:]+):(\d+)$/) or return;

    my $cheap = {
        peer_ip => $address,
        peer_port => $port,
        addr => $addr,
    };

    $self->add_client_obj( $cheap );
   
    $self->reconnect_to_client( $cheap );
    
    undef;
}

sub remote_connect_success {
    my ($kernel, $self, $socket) = @_[KERNEL, OBJECT, ARG0];
    my $cheap = $self->{cheap};
    my $addr = $cheap->{addr} = join(':',@$cheap{qw( peer_ip peer_port )}); 
    $self->_log(v => 3, msg => "Connected to $addr");

    $kernel->alarm_remove(delete $cheap->{timeout_id})
        if (exists($cheap->{timeout_id}));
    
    $cheap->{con} = POE::Wheel::ReadWrite->new(
        Handle       => $socket,
        Driver       => POE::Driver::SysRW->new(),
        Filter       => POE::Filter::Line->new(),
        InputEvent   => $self->create_event($cheap,'remote_input'),
        ErrorEvent   => $self->create_event($cheap,'remote_error'),
        FlushedEvent => $self->create_event($cheap,'remote_flush'),
    );
    delete $cheap->{sf};

    $self->{connections}++;

    $cheap->{connected} = 1;
}

sub remote_connect_error {
    my ($kernel, $self) = @_[KERNEL, OBJECT];

    my $cheap = $self->{cheap};

    $self->_log(v => 2, msg => "Erorr connecting to $cheap->{addr} : $_[ARG0] error $_[ARG1] ($_[ARG2])");

    $kernel->alarm_remove(delete $cheap->{timeout_id})
        if (exists($cheap->{timeout_id}));

    $self->{connections}--;
    
    $self->reconnect_to_client( $cheap );
}

sub remote_connect_timeout {
    my ($kernel, $self) = @_[KERNEL, OBJECT];
    my $cheap = $self->{cheap};

    $self->_log(v => 2, msg => "Timeout connecting to $cheap->{addr}");

    $self->{connections}--;

    $self->reconnect_to_client( $cheap );

    undef;
}

sub remote_input {
    my $self = $_[OBJECT];
    $self->_log(v => 4, msg => "got input");
}

sub remote_error {
    my $self = $_[OBJECT];
    $self->_log(v => 2, msg => "got error ".join(' : ',@_[ARG1 .. ARG2]));
    
    $self->{connections}--;
    
    $self->reconnect_to_client( $self->{cheap} );
}

sub remote_flush {
#    $_[OBJECT]->_log(v => 2, msg => "got flush");
}

# Accept a new connection

sub local_accept {
    my ($kernel, $self, $session, $accept_handle, $peer_addr, $peer_port) = @_[KERNEL, OBJECT, SESSION, ARG0, ARG1, ARG2];

    $peer_addr = inet_ntoa($peer_addr);
    my ($port, $ip) = (sockaddr_in(getsockname($accept_handle)));
    $ip = inet_ntoa($ip);


    my $cheap = {
            handle => $accept_handle,
            local_ip => $ip,
            local_port => $port,
            peer_ip => $peer_addr,
            peer_port => $peer_port,
    };
    
#    if ($kernel->call($session->ID => notify => sb_accept => $cheap)) {
#            close($accept_handle);
#            return 0;
#    }

    $self->add_client_obj( $self->{cheap} = $cheap );
    
    $self->_log(v => 4, msg => "Server received connection on $ip:$port from $peer_addr:$peer_port");
    
    $cheap->{con} = POE::Wheel::ReadWrite->new(
        # on this handle
        Handle          => delete $cheap->{handle}, 
        # using sysread and syswrite
        Driver          => POE::Driver::SysRW->new(BlockSize => 4096), 
        Filter          => POE::Filter::Line->new(),
        # generating this event for requests
        InputEvent      => $self->create_event($cheap,'local_receive'),
        # generating this event for errors
        ErrorEvent      => $self->create_event($cheap,'local_error'),
        # generating this event for all-sent
        FlushedEvent    => $self->create_event($cheap,'local_flushed'),
    );

    if ($self->{opts}->{TimeOut}) {
        $cheap->{time_out} = $kernel->delay_set($self->create_event($cheap,'local_timeout') => $self->{opts}->{TimeOut});
        $self->_log(v => 4, msg => "Timeout set: id ".$cheap->{time_out});
    }
    
    $self->{cheap} = undef;

    $self->{connections}++;
    
    $kernel->yield(send => "$cheap" => "ShortBus Event Server ready.");
}


sub local_receive {
    my $self = $_[OBJECT];
    $self->_log(v => 4, msg => "Receive $_[ARG0]");
    if ($_[ARG0] && $_[ARG0] =~ m/connect (\d+)/) {
        my $c = $self->{opts}{ClientList}->[0];
        my $d = .1;
        for ( 1 .. $1 ) {
            $poe_kernel->delay_set(connect_to_client => $d =>  $c);
            $d += .02;
        }
        $_[KERNEL]->yield(send => "$self->{cheap}" => "adding $1 more connections");
    } else {
        $_[KERNEL]->yield(send => "$self->{cheap}" => $_[ARG0]);
    }
}

sub local_flushed {
#    $_[OBJECT]->_log(v => 2, msg => "Flushed");
}

sub local_error {
    my ($kernel, $self, $session, $operation, $errnum, $errstr) = @_[KERNEL, OBJECT, SESSION, ARG0, ARG1, ARG2];
    $self->_log(v => 1, msg => "Server encountered $operation error $errnum: $errstr");
#    $kernel->call($session->ID => notify => tcp_error => { session => $session->ID, operation => $operation, error_num => $errnum, err_str => $errstr });
    
    $self->cleanup_client( $self->{cheap} );
}

sub local_timeout {
    my $self = $_[OBJECT];
    $self->_log(v => 3, msg => "Timeout");
    
    delete $self->{cheap}->{con};

    $self->cleanup_client( $self->{cheap} );
}

1;
