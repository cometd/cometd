package Cometd::Plugin::EventManager::SQLite;

use Cometd qw( Plugin::EventManager );
use POE qw( Component::EasyDBI );

use base qw( Cometd::Plugin::EventManager );

use DBD::SQLite;

use strict;
use warnings;

sub as_string {
    __PACKAGE__;
}

sub new {
    my $class = shift;
    
    my $self = $class->SUPER::new( @_ );

    $self->{sqlite_file} = 'pubsub.db'
        unless( defined ( $self->{sqlite_file} ) );

    POE::Session->create(
        object_states => [
            $self => [qw(
                _start

                _connected
                check_error
                update_eid

                deliver_event
                get_events
                add_channels

                db_do
                db_select
            )],
        ]
    );

    return $self;
}

sub _start {
    my ($self, $kernel) = @_[OBJECT, KERNEL];

    $kernel->alias_set( $self->{alias} )
        if ( $self->{alias} );

    $self->_log(v => 4, msg => 'connecting to the database');

    $self->{db} = POE::Component::EasyDBI->new(
        alias       => "$self",
        dsn         => 'dbi:SQLite:dbname='.$self->{sqlite_file},
        username    => '',
        password    => '',
        options     => {
            AutoCommit => 1,
        },
        connected => [ $_[SESSION]->ID, '_connected' ],
    ); 
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

sub update_eid {
    my ( $self, $kernel, $r ) = @_[ OBJECT, KERNEL, ARG0 ];
    
    if ( $r->{error} ) {
        warn "$r->{error}";
        return;
    }
    
#    require Data::Dumper;
#    warn "result from event update: ".Data::Dumper->Dump([$r]);
}


sub _connected {
    my ( $self, $kernel ) = @_[OBJECT, KERNEL];

    $self->_log(v => 4, msg => 'connected to db');

    return if ( $self->{first_connect}++ );

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
    
    $self->{db}->do(
        sql => 'DELETE FROM clients',
        event => 'check_error'
    );

    $self->{db}->do(
        sql => 'CREATE TABLE cli_ch_map (clid VaRCHAR(50), channel VARCHAR(255), eid INTEGER)',
        event => 'check_error'
    );
    
    $self->{db}->do(
        sql => 'DELETE FROM cli_ch_map',
        event => 'check_error'
    );
    # need UNIQUE on clid and channel
    
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

    $self->add_client( "test", [ "/foo", "/bar" ] );
    
    # TODO clean out clients
}

# methods

sub add_client {
    my ( $self, $clid, $channel ) = @_;
    
    return unless ( $clid );
    
    $self->add_channels( $clid, $channel )
        if ( $channel );

    $self->{db}->do(
        sql => 'REPLACE INTO clients (clid) VALUES (?)',
        placeholders => [ $clid ],
        event => 'check_error'
    );
}

sub remove_client {
    my ( $self, $clid ) = @_;
    
    return unless ( $clid );
    
    $self->{db}->do(
        sql => 'DELETE FROM cli_ch_map WHERE clid=?',
        placeholders => [ $clid ],
        event => 'check_error'
    );

    $self->{db}->do(
        sql => 'DELETE FROM clients WHERE clid=?',
        placeholders => [ $clid ],
        event => 'check_error'
    );
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
            warn "result from get events: ".Data::Dumper->Dump([$r]);
            
            foreach my $ch ( grep { !exists( $r->{result}->{ $_ } ) } @$channels ) {
                # XXX We may need to get the eid from the events table!
                $self->{db}->do(
                    sql => 'INSERT INTO channels (channel, eid) VALUES (?, 0)',
                    placeholders => [ $ch ],
                    event => 'check_error'
                );
            }

            foreach my $ch ( @$channels ) {
                warn "inserting $ch $clid ".($r->{result}->{$ch} || 0);
                $self->{db}->do(
                    sql => 'INSERT INTO cli_ch_map (channel, clid, eid) VALUES (?, ?, ?)',
                    placeholders => [ $ch, $clid, $r->{result}->{$ch} || 0 ],
                    event => 'check_error'
                );
            }
        }
    );
}

sub remove_channels {
    my ( $self, $clid, $channels ) = @_;
    
    return unless ( $clid && $channels );
    
    $channels = [ $channels ] unless ( ref $channels );

    unshift ( @$channels, $clid );    

    $self->{db}->do(
        sql => 'DELETE FROM cli_ch_map WHERE clid=? AND channel IN ('
            .join(',', map { '?' } @$channels).')',
        placeholders => $channels,
        event => 'check_error'
    );
}

sub deliver_event {
    # XXX hm, ugly
    my ( $self, $ev ) = $_[ KERNEL ] ? @_[ OBJECT, ARG0 ] : @_;
    
    $self->{db}->insert(
        hash => {
            event => $ev->as_string, # XXX in json
            channel => $ev->channel,
        },
        table => 'events',
        last_insert_id => {},
        event => 'update_eid',
        _channel => $ev->channel
    );
}

sub get_events {
    my ( $self, $clid, $callback ) = $_[ KERNEL ] ? @_[ OBJECT, ARG0 .. $#_ ] : @_;
    
    $self->{db}->hashhash(
        sql =>   qq|SELECT cm.channel,cm.eid,c.eid as leid
                    FROM channels AS c
                    JOIN cli_ch_map AS cm
                        ON c.channel=cm.channel
                    WHERE cm.clid=?
                    AND cm.eid < c.eid|,
#        _sql2 => qq|SELECT cm.channel,cm.eid FROM channels AS c JOIN cli_ch_map AS cm ON c.channel=cm.channel WHERE cm.clid="test" AND cm.eid < c.eid|,
        placeholders => [ $clid ],
        primary_key => 'channel',
        event => sub {
            my $r = shift;
            warn "result from get events: ".Data::Dumper->Dump([$r]);
            if ( $r->{error} ) {
                $self->_log(v => 3, msg => "db error: $r->{error}");
                return;
            }

            my @channels = keys %{$r->{result}};
            unless ( @channels ) {
                $callback->([]);
                return;
            }

            my $events = [];
            foreach my $i ( 0 .. $#channels ) {
                my $ch = $channels[ $i ];
                $self->{db}->do(
                    sql => 'UPDATE cli_ch_map SET channel=?,eid=? WHERE clid=?',
                    placeholders => [ $ch, $r->{result}->{$ch}->{leid}, $clid ],
                    event => 'check_error',
                );
                
                $self->{db}->array(
                    sql => 'SELECT event FROM events WHERE channel=? AND eid > ? AND eid <= ?',
                    placeholders => [ $ch, @{$r->{result}->{$ch}}{qw( eid leid )} ],
                    primary_key => 'channel',
                    event => sub {
                        my $s = shift;
                        warn "result from get events: ".Data::Dumper->Dump([$s]);
                        if ( $s->{error} ) {
                            $self->_log(v => 3, msg => "db error: $s->{error}");
                            $callback->($s->{_events});
                            return;
                        }

                        # save events
                        push(@{$s->{_events}}, @{$s->{result}})
                            if (@{$s->{result}});

                        if ( $i == $#channels ) {
                            warn "calling callback";
                            $callback->($s->{_events});
                        }
                    },
                    _events => $events,
                );
            }
        },
    );
}

1;

