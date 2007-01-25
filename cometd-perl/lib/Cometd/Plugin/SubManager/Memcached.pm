package Cometd::Plugin::SubManager::Memcached;

use strict;
use warnings;

use Cometd qw( Plugin::SubManager Plugin::Memcached );
use POE qw( Component::Cometd::Client );

use base qw( Cometd::Plugin::SubManager );

sub new {
    my $class = shift;
    my $self;
    # FIXME
    my $cli = Cometd::Plugin::Memcached->new(
        event_connected => sub {
            $self->event_connected( @_ );
        },
    );
    
    $self = $class->SUPER::new(
        ch => {},
        cid => {},
        # FIXME
        cli => $cli,
        comp => POE::Component::Cometd::Client->spawn(
            LogLevel => 4,
            TimeOut => 0,
            Name => 'Subscription Manager - Memcached',
            ClientList => [
                '127.0.0.1:11211',
            ],
            Transports => [
                {
                    plugin => $cli,
                    priority => 0,
                },
            ],
        ),
        @_
    );
    
    return $self;
}


sub event_connected {
    my $self = shift;
   
    # ok to start
    $self->{cli}->set( { key => 'foo2', obj => 'it worked', callback => sub { my ($r,$s) = @_; warn "$s"; } } );
    $self->{cli}->get( { key => 'foo2', callback => sub { my ($r,$s) = @_; warn "$s"; } } );
}


1;
