package POE::Component::Server::ShortBus;

use strict;
use warnings;

our $VERSION = '0.01';
our $LogLevel = 0;

use Socket;
use Carp;

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
    $opts{TimeOut} = $opts{TimeOut} || 30;
    $opts{ListenAddress} = $opts{ListenAddress} || '0.0.0.0';
    $LogLevel = delete $opts{LogLevel} || 0;

    my $self = bless( { clients => {} }, $package );

    POE::Session->create(
        heap => {
            opts => \%opts,
            clients => {},
        },
       options => { trace=>1 },
       object_states => [
            $self => [qw(
                _start
                _default
                _stop
                register
                unregister
                notify
                accept
                tcp_error
                signals

                send

                client_connect
                got_connect_success
                got_connect_timeout
                got_connect_error
                got_server_error
                got_server_input
                got_server_flush
            )]
        ],
    );

    return 1;
}


sub _start {
    my ($kernel, $session, $heap) = @_[KERNEL, SESSION, HEAP];

    $session->option( @{$heap->{opts}{SessionOptions}} ) if $heap->{opts}{SessionOptions};
    $kernel->alias_set($heap->{opts}{Alias}) if ($heap->{opts}{Alias});

    # watch for SIGINT, SIGCHLD
    foreach (qw( INT CHLD )) {
        $kernel->sig($_ => 'signals');
    }

    # create a socket factory
    $heap->{wheel} = POE::Wheel::SocketFactory->new(
        BindPort       => $heap->{opts}{ListenPort},          # on this port
        BindAddress    => $heap->{opts}{ListenAddress},
        Reuse          => 'yes',          # and allow immediate port reuse
        SuccessEvent   => 'accept',       # generating this event on connection
        FailureEvent   => 'tcp_error'  # generating this event on error
    );
    
    if ($heap->{opts}{ClientList} && ref($heap->{opts}{ClientList}) eq 'ARRAY') {
        foreach (@{$heap->{opts}{ClientList}}) {
            $kernel->yield(client_connect => $_);
        }
    }

    #_write_log(v => 2, msg => "Listening to port $heap->{opts}{ListenPort} on $heap->{opts}{ListenAddress}");
}


sub _stop {
    my ($kernel, $session) = @_[KERNEL, SESSION];
    _write_log(v => 2, msg => "Server stopped.");
}


sub register {
    my($kernel, $heap, $session, $sender) = @_[KERNEL, HEAP, SESSION, SENDER];
    $kernel->refcount_increment($sender->ID, __PACKAGE__);
    $heap->{listeners}->{$sender->ID} = 1;
    $kernel->post($sender->ID => sb_registered => $_[SESSION]->ID);
    _write_log(v => 2, msg => "Listening to port $heap->{opts}{ListenPort} on $heap->{opts}{ListenAddress}");
}


sub unregister {
    my($kernel, $heap, $sender) = @_[KERNEL, HEAP, SENDER];
    $kernel->refcount_decrement($sender->ID, __PACKAGE__);
    delete $heap->{listeners}->{$sender->ID};
}


sub notify {
    my($kernel, $heap, $session, $sender, $name, $data) = @_[KERNEL, HEAP, SESSION, SENDER, ARG0, ARG1];
    
    my $ret = 0;
    foreach (keys %{$heap->{listeners}}) {
        my $tmp = $kernel->call($_ => $name => $data);
        if (defined($tmp)) {
            $ret += $tmp;
        }
    }
    
    return ($ret > 0) ? 1 : 0;
}


# Accept a new connection

sub accept {
    my ($kernel, $heap, $session, $accept_handle, $peer_addr, $peer_port) = @_[KERNEL, HEAP, SESSION, ARG0, ARG1, ARG2];

    $peer_addr = inet_ntoa($peer_addr);
    my ($port, $ip) = (sockaddr_in(getsockname($accept_handle)));
    $ip = inet_ntoa($ip);

    _write_log(v => 2, msg => "Server received connection on $ip ($ip:$port) from $peer_addr:$peer_port");

    my $obj = {
            handle => $accept_handle,
            local_ip => $ip,
            local_port => $port,
            peer_ip => $peer_addr,
            peer_port => $peer_port,
    };
    
    if ($kernel->call($session->ID => notify => sb_accept => $obj)) {
            close($accept_handle);
            return 0;
    }

    $_[OBJECT]->add_client_obj( $obj );
    
    $obj->{control} = POE::Wheel::ReadWrite->new(
        # on this handle
        Handle          => delete $obj->{handle}, 
        # using sysread and syswrite
        Driver          => POE::Driver::SysRW->new(BlockSize => 4096), 
        Filter          => POE::Filter::Line->new(),
        # generating this event for requests
        InputEvent      => "$obj|receive",
        # generating this event for errors
        ErrorEvent      => "$obj|tcp_error",
        # generating this event for all-sent
        FlushedEvent    => "$obj|flushed",
    );

    if ($heap->{opts}->{TimeOut} > 0) {
        $obj->{time_out} = $kernel->delay_set("$obj|time_out" => $heap->{opts}->{TimeOut});
        _write_log(v => 4, msg => "Timeout set: id ".$obj->{time_out});
    }
    
    $kernel->yield(send => "$obj" => "ShortBus Event Server ready.");
    
    undef;
}

sub send {
    my $c = $_[OBJECT]->{clients}->{$_[ARG0]}->{control};
    if ($c) {
        $c->put($_[ARG1]);
    }
}


sub add_client_obj {
    my ($self,$obj) = @_;
    
    $self->{clients}->{"$obj"} = $obj;
    
    undef;
}


sub tcp_error {
    my ($kernel, $session, $operation, $errnum, $errstr) = @_[KERNEL, SESSION, ARG0, ARG1, ARG2];
    _write_log(v => 1, msg => "Server encountered $operation error $errnum: $errstr");
    $kernel->call($session->ID => notify => tcp_error => { session => $session->ID, operation => $operation, error_num => $errnum, err_str => $errstr });
}


# Handle incoming signals (INT)

sub signals {
    my ($kernel, $session, $signal_name) = @_[KERNEL, SESSION, ARG0];

    _write_log(v => 1, msg => "Server caught SIG$signal_name");

    # to stop ctrl-c / INT
    if ($signal_name eq 'INT') {
        #$_[KERNEL]->sig_handled();
    }

    return 0;
}


sub _write_log {
    my (%o) = @_;
    if ($o{v} <= $LogLevel) {
        my $sender = (defined $o{sender_session}) ? $o{sender_session} : "?";
        my $type = (defined $o{type}) ? $o{type} : 'M';

        my $datetime = localtime();
        print STDERR "[$datetime][$type$sender] $o{msg}\n";
    }
}


sub _default {
    my ($kernel, $heap, $self, $session, $cmd, $args) = @_[KERNEL, HEAP, OBJECT, SESSION, ARG0, ARG1];

    if ($cmd !~ /^_/ && $cmd =~ m/^([^\|]+)\|(.*)/) {
        
        return unless ($1 && $2);

        _write_log(v => 2, msg => "dispatching $2 for $1");

        unless (defined(&$2)) {
            _write_log(v => 1, msg => "subroutine $2 does not exist");
            warn "subroutine $2 does not exist";
            return 0;
        }
        
        $heap->{cheap} = ($self->{clients}->{$1}) ? $self->{clients}->{$1} : undef;
        
        splice(@_, ARG0, $#_, @$args, $1);
       
        no strict 'refs';
        &$2;
    }
    
    return 0;
}


sub client_connect {
    my ($kernel, $heap, $addy) = @_[KERNEL, HEAP, ARG0];

    my ($address, $port) = ($addy =~ /^([^:]+):(\d+)$/) or return;

    return if ($heap->{cheap});
    
    my $cheap = $_[OBJECT]->{clients}->{$addy} = { addy => $addy };

    $cheap->{timeout_id} = $kernel->alarm_set(
        $addy.'|got_connect_timeout' => time + 45
    );

    $cheap->{con} = POE::Wheel::SocketFactory->new(
        RemoteAddress => $address,
        RemotePort    => $port,
        SuccessEvent  => $addy.'|got_connect_success',
        FailureEvent  => $addy.'|got_connect_error',
    );
}


sub got_connect_success {
    my ($kernel, $heap, $socket) = @_[KERNEL, HEAP, ARG0];

    my $cheap = $heap->{cheap};
    my $addy = $cheap->{addy};

    _write_log(v => 2, msg => "Connected to $cheap->{addy}");

    $kernel->alarm_remove(delete $cheap->{timeout_id})
        if (exists($cheap->{timeout_id}));

    $cheap->{con} = POE::Wheel::ReadWrite->new(
        Handle       => $socket,
        Driver       => POE::Driver::SysRW->new(),
        Filter       => POE::Filter::Line->new(),
        InputEvent   => $addy.'|got_server_input',
        ErrorEvent   => $addy.'|got_server_error',
        FlushedEvent => $addy.'|got_server_flush',
    );

    $cheap->{connected} = 1;
}


sub got_connect_error {
    my ($kernel, $heap) = @_[KERNEL, HEAP];

    my $cheap = $heap->{cheap};

    _write_log(v => 2, msg => "Erorr connecting to $cheap->{addy} : $_[ARG0] error $_[ARG1] ($_[ARG2])");

    $kernel->alarm_remove(delete $cheap->{timeout_id})
        if (exists($cheap->{timeout_id}));

    $cheap->{connected} = 0;

    # TODO reconnect
}


sub got_connect_timeout {
    my ($kernel, $heap, $addy) = @_[KERNEL, HEAP, ARG0];

    _write_log(v => 2, msg => "Timeout connecting to $addy");

    $heap->{cheap}->{connected} = 0;

    # TODO reconnect
}


sub got_server_input {
    print STDERR "got input\n";
}


sub got_server_error {
    print STDERR "got error\n";
}


sub got_server_flush {
    print STDERR "got push\n";
}

sub flushed {

}

sub time_out {

}

1;
