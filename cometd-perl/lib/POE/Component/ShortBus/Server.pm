package POE::Component::Cometd::Server;

use POE qw( Component::Cometd );

use base qw( POE::Component::Cometd );

use Socket;
use overload '""' => \&as_string;

sub spawn {
    my $self = shift->SUPER::new(@_);
   
    POE::Session->create(
#       options => { trace=>1 },
       heap => $self,
       object_states => [
            $self => [qw(
                _start
                _default
                _stop
                register
                unregister
                notify
                signals
                send

                local_accept
                local_receive
                local_flushed
                local_error
                local_timeout
            )]
        ],
    );

    return $self;
}

sub as_string {
    __PACKAGE__;
}

sub _start {
    my ($kernel, $session, $self) = @_[KERNEL, SESSION, OBJECT];

    $session->option( @{$self->{opts}{ServerSessionOptions}} ) if $self->{opts}{ServerSessionOptions};
    $kernel->alias_set($self->{opts}{ServerAlias}) if ($self->{opts}{ServerAlias});

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

    $self->_log(v => 2, msg => "Listening to port $self->{opts}{ListenPort} on $self->{opts}{ListenAddress}");

}

sub _stop {
    $_[OBJECT]->_log(v => 2, msg => "Server stopped.");
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
    
    $kernel->yield(send => "$cheap" => "Cometd Event Server ready.");
    
    $self->{transport}->process_plugins( $accept_handle, $cheap->{con}, $cheap );
}


sub local_receive {
    my $self = $_[OBJECT];
    $self->_log(v => 4, msg => "Receive $_[ARG0]");
    $_[KERNEL]->yield(send => "$self->{cheap}" => $_[ARG0]);
}

sub local_flushed {
#    $_[OBJECT]->_log(v => 2, msg => "Flushed");
}

sub local_error {
    my ($kernel, $self, $session, $operation, $errnum, $errstr) = @_[KERNEL, OBJECT, SESSION, ARG0, ARG1, ARG2];
    $self->_log(v => 1, msg => "Server encountered $operation error $errnum: $errstr heap($self->{cheap})");
#    $kernel->call($session->ID => notify => tcp_error => { session => $session->ID, operation => $operation, error_num => $errnum, err_str => $errstr });
    
    $self->cleanup_connection( $self->{cheap} );
}

sub local_timeout {
    my $self = $_[OBJECT];
    $self->_log(v => 3, msg => "Timeout");
    
    delete $self->{cheap}->{con};

    $self->cleanup_connection( $self->{cheap} );
}
1;
