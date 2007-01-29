package Cometd::Plugin::SubManager::Memcached;

use strict;
use warnings;

use Cometd qw( Plugin::SubManager Plugin::Memcached );
use POE qw( Component::Cometd::Client );
use Data::Dumper;

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
 
    warn "connected";

    $self->add_channel('test','/foo/bar');
    # ok to start
#    $self->{cli}->set( { key => 'foo2', obj => 'it worked', callback => sub { my ($r,$s) = @_; warn "$s"; } } );
#    $self->{cli}->get( { key => 'foo2', callback => sub { my ($r,$s) = @_; warn "$s"; } } );
}

sub remove_channel {
    shift->add_channel( @_[ 0, 1 ], 1 );
}

sub add_channel {
    my ( $self, $cid, $channel, $remove ) = @_;

    $self->{cli}->get({
        key => 'ch-'.$cid,
        callback => sub {
            my ($r, $s) = @_;
            warn $self->dump($s);
            
            my $data;
            if ( $s->{data} && $s->{data}->{'ch-'.$cid} ) {
                $data = eval($s->{data}->{'ch-'.$cid}->{obj});
                warn "error: $@" if ($@);
                warn "data:".$self->dump($data);
                if ( $remove ) {
                    delete $data->{$channel};
                } else {
                    $data->{$channel} = 0;
                }
            } else {
                return if ( $remove );
                $data = { $channel => 0 };
            }
            
            $self->{cli}->set({
                key => 'ch-'.$cid,
                obj => $self->dump($data),
                callback => sub {
                    my ($r, $s) = @_;
                    warn $self->dump($s);
                }
            });
        }
    });
}

sub dump {
    my $self = shift;
    local $Data::Dumper::Indent = 0;
    my $d = Data::Dumper->Dump([$_[0]],['']);
    return substr($d,4);
}

1;
