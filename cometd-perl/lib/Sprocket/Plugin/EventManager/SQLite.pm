package Sprocket::Plugin::EventManager::SQLite;

use Sprocket qw( Plugin::EventManager );
use POE qw( Component::EasyDBI );

use base qw( Sprocket::Plugin::EventManager );

# just checking
use DBD::SQLite;

use HTTP::Date qw( str2time );

use strict;
use warnings;

sub as_string {
    __PACKAGE__;
}

sub new {
    my $class = shift;
    
    my $self = $class->SUPER::new( @_ );

    # channels receive events and update this
    # the watchog checks this to notify anything
    # of channel activity
    $self->{channels} = {};

    $self->{sqlite_file} = 'pubsub.db'
        unless( defined ( $self->{sqlite_file} ) );

    $self->{session_id} = POE::Session->create(
        object_states => [
            $self => [qw(
                _start

                _connected
                set_watchdog
                watchdog
                
                check_error
                update_eid

                deliver_events
                get_events
                add_client
                remove_client
                add_channels
                remove_channels

                remove_cid

                db_do
                db_select
            )],
        ]
    )->ID();

    return $self;
}

sub _start {
    my ($self, $kernel) = @_[OBJECT, KERNEL];

    $kernel->alias_set( $self->{alias} )
        if ( $self->{alias} );

    $self->{watchdog_time} ||= .2;

    $kernel->delay_set( watchdog => $self->{watchdog_time} );

    $self->_log(v => 4, msg => 'connecting to the database');

    $self->{db} = POE::Component::EasyDBI->new(
        alias       => "$self",
        dsn         => 'dbi:SQLite:dbname='.$self->{sqlite_file},
        username    => '',
        password    => '',
        options     => {
            AutoCommit => 1,
        },
        max_retries => -1,
        reconnect_wait => 1,
        connected => [ $_[SESSION]->ID, '_connected' ],
#        query_logger => 'debug_logger',
    );
}

sub set_watchdog {
    my ($self, $kernel) = @_[OBJECT, KERNEL];
    $kernel->delay_set( watchdog => $self->{watchdog_time} );
}

sub watchdog {
    my ($self, $kernel) = @_[OBJECT, KERNEL];
    
    my @chans = keys %{$self->{channels}};
    
    $kernel->delay_set( watchdog => $self->{watchdog_time} ) unless ( @chans );
    
    return unless ( @chans );
    
    $self->{db}->hashhash(
        sql =>   qq|SELECT c.cid, c.clid, cl.acttime
                    FROM cid_clid_map AS c
                    JOIN clients AS cl
                        ON c.clid=cl.clid
                    JOIN clid_ch_map AS cm
                        ON c.clid=cm.clid
                    WHERE cm.channel IN (|
                .join(',', map { '?' } @chans).')',
        placeholders => \@chans,
        primary_key => 'cid',
        event => sub {
            my $r = shift;
            my @del;
#            require Data::Dumper;
#            warn "".Data::Dumper->Dump([$r]);
            foreach my $cid (keys %{$r->{result}}) {
                my $acttime = str2time( $r->{result}->{$cid}->{acttime} );
                $self->_log( v => 4, msg => sprintf( 'cid: %s, clid: %s last act: %s = %s ( - %s )', $cid, $r->{result}->{$cid}->{clid}, $r->{result}->{$cid}->{acttime}, $acttime, ( time() - ( 5 * 60 ) ) ) );
                
                if ( $acttime < ( time() - ( 5 * 60 ) ) ) {
                    push( @del, $r->{result}->{$cid}->{clid} );
                } else {
                    # XXX ugly!!
                    $poe_kernel->post( $self->{parent_id} => $cid.'|events_ready' => $r->{result}->{$cid}->{clid} );
                }
            }
            
            foreach ( @del ) {
                $self->_log( v => 4, msg => sprintf( 'cid: %s scheduled for deletion', $_ ) );
                $self->remove_client( $_ );
            }
            $kernel->call( $self->{session_id} => 'set_watchdog' );
        }
    );
    
    $self->{channels} = {};
}

# XXX temp
sub check_error {
    my ( $self, $e ) = @_[OBJECT, ARG0];

    $self->_log(v => 4, msg => 'error '.$e->{error}) if ($e->{error});
}

sub db_do {
    my ($self, $kernel, $sql, $coderef) = @_[OBJECT, KERNEL, ARG0, ARG1];
    $self->{db}->do(
        sql => $sql,
        event => $coderef
    );
}

sub db_select {
    my ($self, $kernel, $sql, $coderef) = @_[OBJECT, KERNEL, ARG0, ARG1];
    $self->{db}->arrayhash(
        sql => $sql,
        event => $coderef
    );
}

# XXX not used
sub update_eid {
    my ( $self, $kernel, $r ) = @_[ OBJECT, KERNEL, ARG0 ];
    
    if ( $r->{error} ) {
#        warn "$r->{error}";
        return;
    }
    
    $self->{channels}->{$r->{_channel}} = $r->{insert_id}
        #if ( exists( $r->{insert_id} ) )
    
#    require Data::Dumper;
#    warn "result from event update: ".Data::Dumper->Dump([$r]);
}


sub _connected {
    my ( $self, $kernel ) = @_[OBJECT, KERNEL];

    $self->_log(v => 4, msg => 'connected to db');

    return if ( $self->{first_connect}++ );

    $self->{db}->single(
        sql => 'SELECT 1 FROM clients',
        event => sub {
            return unless ( $_[0]->{error} );
            
            $self->{db}->do(
                sql => 'CREATE TABLE clients (clid VARCHAR(50) PRIMARY KEY, acttime DATE)',
                event => 'check_error'
            );
            
            $self->{db}->do(
                sql => qq(CREATE TRIGGER update_clients AFTER INSERT ON clients
             BEGIN
              UPDATE clients SET acttime = DATETIME('NOW') WHERE clid=new.clid;
             END;),
                event => 'check_error'
            );
            
            #$self->{db}->do(
            #    sql => 'DELETE FROM clients',
            #    event => 'check_error'
            #);
        
            $self->{db}->do(
                sql => 'CREATE TABLE clid_ch_map (clid VARCHAR(50), channel VARCHAR(255), eid INTEGER)',
                event => 'check_error'
            );
            
            $self->{db}->do(
                sql => 'CREATE UNIQUE INDEX unique_clid_ch_map ON clid_ch_map (clid, channel)',
                event => 'check_error',
            );
            
            #$self->{db}->do(
            #    sql => 'DELETE FROM clid_ch_map',
            #    event => 'check_error'
            #);
            
            $self->{db}->do(
                sql => 'CREATE TABLE channels (channel VARCHAR(255) PRIMARY KEY, eid INTEGER)',
                event => 'check_error'
            );
            
            # XXX blob?
            $self->{db}->do(
                sql => 'CREATE TABLE events (eid INTEGER PRIMARY KEY, channel VARCHAR(255), event TEXT)',
                event => 'check_error'
            );
            
            $self->{db}->do(
                sql => qq(CREATE TRIGGER insert_events AFTER INSERT ON events
             BEGIN
              UPDATE channels SET eid=new.eid WHERE channel=new.channel;
             END;),
                event => 'check_error'
            );
            
            $self->{db}->do(
                sql => 'CREATE TABLE cid_clid_map (cid VARCHAR(10), clid VARCHAR(50) UNIQUE)',
                event => 'check_error'
            );
    
            $self->_log( v => 4, msg => 'tables created' ); 
        }
    );

    # connection ids are nolonger valid, delete them
    $self->{db}->do(
        sql => 'DELETE FROM cid_clid_map',
        event => 'check_error'
    );
}

# methods

sub add_client {
    my ( $self, $clid, $cid, $channel ) = $_[ KERNEL ] ? @_[ OBJECT, ARG0 .. $#_ ] : @_;
    
    return unless ( $clid );
    
    $self->add_channels( $clid, $channel )
        if ( defined $channel );
    
    $self->{db}->do(
        sql => 'REPLACE INTO cid_clid_map (cid,clid) VALUES (?,?)',
        placeholders => [ $cid, $clid ],
        event => 'check_error'
    ) if ( $cid );

    $self->{db}->do(
        sql => 'REPLACE INTO clients (clid) VALUES (?)',
        placeholders => [ $clid ],
        event => 'check_error'
    );

    # post style
    return [ $self->{session_id} => remove_cid => $cid ];
}

sub remove_cid {
    my ( $self, $cid ) = $_[ KERNEL ] ? @_[ OBJECT, ARG0 ] : @_;

    $self->_log(v => 4, msg => "cleanup of con id: ".$cid );
    
    $self->{db}->do(
        sql => 'DELETE FROM cid_clid_map WHERE cid=?',
        placeholders => [ $cid ],
        event => 'check_error'
    );
}

sub remove_client {
    my ( $self, $clid ) = $_[ KERNEL ] ? @_[ OBJECT, ARG0 .. $#_ ] : @_;
    
    return unless ( $clid );
    
    $self->{db}->do(
        sql => 'DELETE FROM clid_ch_map WHERE clid=?',
        placeholders => [ $clid ],
        event => 'check_error'
    );
    
    $self->{db}->do(
        sql => 'DELETE FROM cid_clid_map WHERE clid=?',
        placeholders => [ $clid ],
        event => 'check_error'
    );

    $self->{db}->do(
        sql => 'DELETE FROM clients WHERE clid=?',
        placeholders => [ $clid ],
        event => 'check_error'
    );
    
    warn "remove client $clid";
}

sub add_channels {
    my ( $self, $clid, $channels ) = $_[ KERNEL ] ? @_[ OBJECT, ARG0 .. $#_ ] : @_;
 
    return unless ( $clid && $channels );

    $channels = [ $channels ] unless ( ref $channels );

    $self->{db}->keyvalhash(
        sql => 'SELECT channel,eid FROM channels WHERE channel IN ('
            .join(',', map { '?' } @$channels).')',
        placeholders => $channels,
        primary_key => 'channel',
        event => sub {
            my $r = shift;
            if ( $r->{error} ) {
                $self->_log(v => 3, msg => "db error: $r->{error}");
#                return;
                $r->{result} = {};
            }
            #warn "result from get events: ".Data::Dumper->Dump([$r]);
            
            foreach my $ch ( grep { !exists( $r->{result}->{ $_ } ) } @$channels ) {
                # XXX We may need to get the eid from the events table!
                $self->{db}->do(
                    sql => 'INSERT INTO channels (channel, eid) VALUES (?, 0)',
                    placeholders => [ $ch ],
                    event => 'check_error'
                );
            }

            foreach my $ch ( @$channels ) {
                #warn "inserting $ch $clid ".($r->{result}->{$ch} || 0);
                
                $self->{db}->do(
                    sql => 'INSERT INTO clid_ch_map (channel, clid, eid) VALUES (?, ?, ?)',
                    placeholders => [ $ch, $clid, $r->{result}->{$ch} || 0 ],
                    event => 'check_error'
                );
            }
        }
    );
}

sub remove_channels {
    my ( $self, $clid, $channels ) = $_[ KERNEL ] ? @_[ OBJECT, ARG0 .. $#_ ] : @_;
    
    return unless ( $clid && $channels );
    
    $channels = [ $channels ] unless ( ref $channels );

    $self->{db}->do(
        sql => 'DELETE FROM clid_ch_map WHERE clid=? AND channel IN ('
            .join(',', map { '?' } @$channels).')',
        placeholders => [ $clid, @$channels ],
        event => 'check_error'
    );
}

sub deliver_events {
    my ( $self, $ev ) = $_[ KERNEL ] ? @_[ OBJECT, ARG0 .. $#_ ] : @_;
    
    $ev = [ $ev ] unless ( ref( $ev ) eq 'ARRAY' );
    
    foreach ( @$ev ) {
        my $ch = $_->channel;
        $self->{db}->insert(
            hash => {
                event => $_->as_string,
                channel => $ch,
            },
            table => 'events',
            event => sub { $self->{channels}->{ $_[0]->{_channel} } = 1; },
            _channel => $ch
        );
    }
}

sub get_events {
    my ( $self, $clid, $callback ) = $_[ KERNEL ] ? @_[ OBJECT, ARG0 .. $#_ ] : @_;
    
    $self->{db}->hashhash(
        sql =>   qq|SELECT cm.channel,cm.eid,c.eid as leid
                    FROM channels AS c
                    JOIN clid_ch_map AS cm
                        ON c.channel=cm.channel
                    WHERE cm.clid=?
                    AND cm.eid < c.eid|,
#        _sql2 => qq|SELECT cm.channel,cm.eid FROM channels AS c JOIN clid_ch_map AS cm ON c.channel=cm.channel WHERE cm.clid="test" AND cm.eid < c.eid|,
        placeholders => [ $clid ],
        primary_key => 'channel',
        event => sub {
            my $r = shift;
            #warn "result from get events: ".Data::Dumper->Dump([$r]);
            if ( $r->{error} ) {
                $self->_log(v => 3, msg => "db error: $r->{error}");
                return;
            }

            my @channels = keys %{$r->{result}};
            unless ( @channels ) {
                if ( ref( $callback ) eq 'CODE' ) {
                    $callback->( [] );
                } else {
                    $poe_kernel->call( @$callback => [] );
                }
                return;
            }

            my $events = [];
            my $lastchan = $#channels;
            foreach my $i ( 0 .. $lastchan ) {
                my $ch = $channels[ $i ];
                $self->{db}->do(
                    sql => 'UPDATE clid_ch_map SET eid=? WHERE clid=? AND channel=?',
                    placeholders => [ $r->{result}->{$ch}->{leid}, $clid, $ch ],
                    event => 'check_error',
                );
                
                $self->{db}->arrayhash(
                    sql => 'SELECT eid,event FROM events WHERE channel=? AND eid > ? AND eid <= ? ORDER BY eid ASC',
                    placeholders => [ $ch, @{$r->{result}->{$ch}}{qw( eid leid )} ],
                    primary_key => 'channel',
                    #chunked => 50,
                    event => sub {
                        my $s = shift;
                        #warn "result from get events: ".Data::Dumper->Dump([$s]);
                        if ( $s->{error} ) {
                            $self->_log(v => 3, msg => "db error: $s->{error}");
                            if ( ref( $callback ) eq 'CODE' ) {
                                $callback->( $s->{_events} );
                            } else {
                                $poe_kernel->call( @$callback => $s->{_events} );
                            }
                            return;
                        }

                        # save events
                        push(@{$s->{_events}}, @{$s->{result}})
                            if (@{$s->{result}});

                        if ( $i == $lastchan ) {
                            if ( ref( $callback ) eq 'CODE' ) {
                                $callback->( $s->{_events} );
                            } else {
                                $poe_kernel->call( @$callback => $s->{_events} );
                            }
                        }
                    },
                    _events => $events,
                );
            }
        },
    );
}

1;

