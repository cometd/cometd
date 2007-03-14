package Perlbal::Plugin::Cometd;

use strict;
use warnings;

use Sprocket qw(
    Service::HTTPD
    Filter::JSON
    Perlbal::Service::Connector
);
use base 'Sprocket::Service::HTTPD';
use Carp qw( croak );

use constant KEEPALIVE_TIMER => 1;

our %objects = ();

sub new {
    my $self = shift->SUPER::new(@_);
    
    $objects{$self} = 1;

    $self->{filter} = Sprocket::Filter::JSON->new();

    $self;
}

sub register {
    my $service = $_[ 1 ];
    my $self = __PACKAGE__->new();
 
#   Danga::Socket->AddTimer( KEEPALIVE_TIMER, sub { $self->time_keepalive(); } );
    $service->register_hook(
        "Sprocket|$self" => start_proxy_request => sub {
            $self->start_proxy_request(@_);
        }
    );
    
    # once?
    Perlbal::Service::add_role(
        cometd => sub {
            Sprocket::Perlbal::Service::Connector->new( @_ );
        }
    );

    return 1;
}

sub unregister {
    my $service = $_[ 1 ];
    
    foreach my $self (keys %objects) {
        $service->uregister_hook( "Sprocket|$self" => 'start_proxy_request' );
        delete $objects{$self};
    }

    Perlbal::Service::remove_role( 'cometd' );
    
    return 1;
}

sub load { 
    return 1;
}

sub unload {
    %objects = ();
    warn "unload";
    return 1;
}

sub start_proxy_request {
    my $self = shift;
    my Perlbal::ClientProxy $client = shift;
    
    my Perlbal::HTTPHeaders $head = $client->{req_headers};
    return 0 unless $head;
   
    # requires patched Perlbal, backend response headers
    my Perlbal::HTTPHeaders $hd = $client->{res_headers};
    unless ( $hd ) {
        # XXX no res_headers are available when cometd isn't reproxied
        warn "You are running an old version of Perlbal, please use check out the lastest build in svn at code.sixapart.com\n";
        return 0;
    }

    delete $client->{res_headers};

    return $self->handle_request( $client, $hd );
}

# called from Sprocket::Perlbal::Service::Connector

sub handle_event {
    my $self = shift;
    my $objs = shift;
    my $cleanup = 0;

    warn "handle event";

    $objs = [ $objs ] unless ( ref( $objs ) eq 'ARRAY' );
    
    my $time = time();

    foreach my $o ( @$objs ) {
        next unless ( ref( $o ) eq 'HASH' );
        next if ( !$o->{channel} );
         
        $o->{timestamp} = $time; # XXX
        
        my $ijson = $self->iframe_filter( $o->{channel}, $o );
        my $json = objToJson( $o )."\n";
        
        warn "event from backend: $json";
        
        if ( $o->{channel} =~ m~^/meta/clients/(.*)~ ) {
            if ( exists( $self->{ids}->{ $1 } ) ) {
                my $l = $self->{ids}->{ $1 };
                $l->watch_write(0) if $l->write( $l->{scratch}{tunnel} eq 'iframe' ? $ijson : $json );
                $l->{alive_time} = $time;
                next;
            }
        }

        my $id;
        my $action;
        if ( $o->{channel} =~ m~^/meta/(.*)~ ) {
            my $meta = $1;
            warn "meta channel: $meta";
            
            next if ( !$o->{clientId} );
           
            if ( $meta eq 'internal/reconnect' && $o->{channels}
                && ref($o->{channels}) eq 'ARRAY' ) {
                $action = 'add_chs';
                $id = $o->{clientId};
                warn "channel update for $id!";
            }
            
            if ( $meta eq 'subscribe' && $o->{subscription} ) {
                if ( $o->{successful} ne 'false' ) {
                    # add channel
                    $action = 'add_ch';
                }
                $id = $o->{clientId};
            }

            if ( $meta eq 'unsubscribe' && $o->{subscription} ) {
                if ( $o->{successful} ne 'false' ) {
                    # remove channel
                    $action = 'rem_ch';
                }
                $id = $o->{clientId};
            }

            if ( $meta eq 'disconnect' ) {
                $action = 'dis_cli';
                $id = $o->{clientId};
            }

            if ( $meta eq 'ping' || $meta eq 'handshake'
                || $meta eq 'connect' || $meta eq 'reconnect' ) {
                # XXX is reconnect sent back to client, or is connect used
                $id = $o->{clientId}; 
            }

            if ( $id ) {
                my $l = $self->{ids}->{ $id };
                if ( !$l ) {
                    warn "client id: $id not in id list: ".join(',',keys %{$self->{ids}});
                    next;
                }
                
                unless ( $action eq 'add_chs' ) {
                    warn "out to $id: $json";
                    $l->watch_write(0) if $l->write( $l->{scratch}{tunnel} eq 'iframe' ? $ijson : $json );
                    $l->{alive_time} = $time;
                }

                if ( $action ) {
                    if ( $action eq 'add_ch' ) {
                        $l->{scratch}{ch}->{ $o->{subscription} } = 1;
                    } elsif ( $action eq 'add_chs' ) {
                        warn "+++++adding channels to client $id";
                        foreach ( @{$o->{channels}} ) {
                            warn "adding channel $_";
                            $l->{scratch}{ch}->{ $_ } = 1;
                        }
                    } elsif ( $action eq 'rem_ch' ) {
#                        delete $l->{scratch}{ch}->{ $o->{subscription} };
                    } elsif ( $action eq 'dis_cli' ) {
                        # XXX is close immediate, or at least set {closed}
                        # we need to send the disconnect in the cleanup
                        warn "closing client";
                        $l->close();
                        $cleanup = 1;
                    }
                }

                next;
            } else {
                warn "unhandled meta channel $o->{channel}";
                next;
            }
        }

        warn "at client loop";
        
        LISTENER:
        foreach my $l ( @{$self->{clients}} ) {
            #next if ( $client && $l == $client );
            my $id = $l->{scratch}{id};
       
            warn "at client: $id";

            if ( $l->{closed} ) {
                warn "this client is closed: $id";
                if ( $l->{scratch}{tunnel} eq 'iframe' ) {
                    $self->multiplex_send({
                        channel => '/meta/disconnect',
                        clientId => $l->{scratch}{id},
                        data => {
                            channels => [ keys %{$l->{scratch}{ch}} ],
                        },
                    });
                }
                $cleanup = 1;
                next;
            }
        
            # loop over client channels and deliver event
            # TODO more efficient
            warn "event for channel $o->{channel}";
            
            warn "channels this client $id has: ".join(',',(keys %{$l->{scratch}{ch}}));
            #if ( exists( $l->{scratch}{ch}{ $o->{channel} } ) ) {
                if ( $o->{clientId} && $o->{clientId} eq $l->{scratch}{id} ) {
                    warn "NOT sending event back to client\n";
                } else {
                    warn "******** delivering event on channel $o->{channel} : $id : $json";
                    $l->watch_write(0) if $l->write( $l->{scratch}{tunnel} eq 'iframe' ? $ijson : $json );
                    if ( $l->{scratch}{tunnel} eq 'long-polling' ) {
                        if ( !$l->{scratch}{close_after} ) {
                            $l->{scratch}{close_after} = time() + 1;
                        }
                        next;
                    }
                }
                $l->{alive_time} = $time;
            #}    
        } # end LISTENER

    }

    # TODO client channels
    
    if ( $cleanup ) {
        @{$self->{clients}} = map {
            if ( !$_->{closed} ) {
                weaken( $_ );
                $_;
            } else {
                if ( $_->{scratch}{tunnel} && $_->{scratch}{tunnel} eq 'long-polling' ) {
                    # XXX right thing to do?
                } else {
                    $self->multiplex_send({
                        channel => '/meta/disconnect',
                        clientId => $_->{scratch}{id},
                        data => {
                            channels => [ keys %{$_->{scratch}{ch}} ],
                        },
                    });
                }
                delete $self->{ids}->{ $_->{scratch}{id} } if ( $self->{ids}->{ $_->{scratch}{id} } );
                ();
            }
        } @{$self->{clients}};
    }

}

sub bcast_event {
    my ($self, $ch, $obj, $client) = @_;
    my $cleanup;
    
    my $time = time();
    my $json = $self->iframe_filter( $obj->{channel} = $ch, $obj );
    # TODO client channels
    foreach ( @{$self->{clients}} ) {
        next if ( $client && $_ == $client );
        
        if ( $_->{closed} ) {
            $cleanup = 1;
            next;
        }
        
        $_->{alive_time} = $time;
        
        $_->watch_write(0) if $_->write( $json );
    }
    
    if ( $cleanup ) {
        @{$self->{clients}} = map {
            if ( !$_->{closed} ) {
                weaken( $_ );
                $_;
            } else {
                $self->multiplex_send({
                    channel => '/meta/disconnect',
                    clientId => $_->{scratch}{id},
                    data => {
                        channels => [ keys %{$_->{scratch}{ch}} ],
                    },
                });
                delete $self->{ids}->{ $_->{scratch}{id} };
                ();
            }
        } @{$self->{clients}};
    }

}

sub close_overdue_clients {
    my $self = shift;
    my $cleanup;
    
    my $time = time();
    
    foreach my $c ( @{$self->{clients}} ) {
        if ( $c->{closed} ) {
            $cleanup = 1;
            next;
        }

        if ( my $after = $c->{scratch}{close_after} ) {
            if ( $after >= $time ) {
                $c->close();
            }
        }
    }
    
    return if ( !$cleanup );
    
    @{$self->{clients}} = map {
        if ( !$_->{closed} ) {
            weaken( $_ );
            $_;
        } else {
            if ( my $tunnel = $_->{scratch}{tunnel} ) {
                # XXX
                if ( $tunnel eq 'iframe' ) {
                    $self->multiplex_send({
                        channel => '/meta/disconnect',
                        clientId => $_->{scratch}{id},
                        data => {
                            channels => [ keys %{$_->{scratch}{ch}} ],
                        },
                    });
                }
            }
            delete $self->{ids}->{ $_->{scratch}{id} };
            ();
        }
    } @{$self->{clients}};
}

sub iframe_filter {
    my ($self, $ch, $obj) = @_;

    $obj->{channel} = $ch;

    $self->{filter}->freeze($obj);
}

    
sub time_keepalive {
    my $self = shift;

    Danga::Socket->AddTimer( KEEPALIVE_TIMER, sub { $self->time_keepalive(); } );
    
#    $self->bcast_event( '/meta/ping' => { 'time' => time() } );
    
    $self->close_overdue_clients();
    
#    $self->multiplex_send({
#        channel => '/meta/ping',
#    });
    return 5;
}

sub new_request {
    shift;
    my $client = shift;
    return $client->{res_headers} = Perlbal::HTTPHeaders->new_response(@_);
}
    
sub multiplex_send {
    shift;
    return &Sprocket::Perlbal::Service::Connector::multiplex_send;
}


1;

__END__

=pod

=head1 NAME

Perlbal::Plugin::Cometd - Perlbal plugin for Cometd

=head1 SYNOPSIS
    # perlbal.conf

    LOAD Cometd
    LOAD vhosts

    SERVER max_connections = 10000

    CREATE POOL apache
        POOL apache ADD 127.0.0.1:81
    CREATE SERVICE apache_proxy
        SET role = reverse_proxy
        SET pool = apache
        SET persist_backend = on
        SET backend_persist_cache = 2
        SET verify_backend = on
        SET enable_reproxy = true
    ENABLE apache_proxy

    CREATE SERVICE cometd
        SET role = reverse_proxy
        SET plugins = Cometd
    ENABLE cometd 

    CREATE SERVICE web
        SET listen         = 10.0.0.1:80
        SET role           = selector
        SET plugins        = vhosts
        SET persist_client = on
        VHOST *.yoursite.com = apache_proxy
        VHOST *              = apache_proxy
    ENABLE web

=head1 ABSTRACT

This plugin allows Perlbal to put clients into a push type connection state.

=head1 DESCRIPTION

This Plugin works by keeping a conneciton open after an external webserver has
authorized the client.  That way, your valuable http processes can continue
serving other clients.

The easiest way to use this module is to setup apache on a port other than 80,
and Perlbal on port 80 as in the synopsis.  Start perbal and use a supported javascript
library.  You can find supported libraries and more info at L<http://cometd.com/>

=head1 EXPORT

Nothing.

=head1 SEE ALSO

L<Sprocket>, L<POE>, L<Perlbal>

=head1 AUTHOR

David Davis E<lt>xantus@cometd.comE<gt>

=head1 COPYRIGHT AND LICENSE

Copyright 2006-2007 by David Davis

See the LICENSE file

=cut

