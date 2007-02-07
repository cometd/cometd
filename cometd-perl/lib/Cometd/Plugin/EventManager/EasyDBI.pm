package Cometd::Plugin::EventManager::EasyDBI;

use Cometd qw( Plugin::EventManager );
use POE qw( Component::EasyDBI );

use base qw( Cometd::Plugin::EventManager );
use strict;
use warnings;

sub as_string {
    __PACKAGE__;
}

sub new {
    my $class = shift;
    
    my $self = $class->SUPER::new( @_ );

    $self->{sqlite_file} ||= 'cometd.db';

    POE::Session->create(
        object_states => [
            $self => [qw(
                _start

                connected
                check_error
                update_eid

                deliver_event

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

    POE::Component::EasyDBI->spawn(
        alias       => "$self",
        dsn         => 'dbi:SQLite:dbname='.$self->{sqlite_file},
        username    => '',
        password    => '',
        options     => {
            AutoCommit => 1,
        },
        connected => [ $_[SESSION]->ID, 'connected' ],
    ); 
}

# XXX temp
sub check_error {
    my ( $self, $e ) = @_[OBJECT, ARG0];

    $self->_log(v => 4, msg => 'error '.$e->{error}) if ($e->{error});
}

sub db_do {
    my ($self, $kernel, $sql, $coderef) = @_[OBJECT, KERNEL, ARG0, ARG1];
    $kernel->call( "$self" => do => {
        sql => $sql,
        event => $coderef
    });
}

sub db_select {
    my ($self, $kernel, $sql, $coderef) = @_[OBJECT, KERNEL, ARG0, ARG1];
    $kernel->call( "$self" => arrayhash => {
        sql => $sql,
        event => $coderef
    });
}

sub update_eid {
    my ( $self, $kernel, $r ) = @_[ OBJECT, KERNEL, ARG0 ];
    
    if ( $r->{error} ) {
        warn "$r->{error}";
        return;
    }
    
#    require Data::Dumper;
#    warn "result from event update: ".Data::Dumper->Dump([$r]);

    # TODO update channels?
# XXX using triggers now
#    $kernel->call( "$self" => do => {
#        sql => 'UPDATE channels SET eid=? WHERE channel=?',
#        placeholders => [ $r->{insert_id}, $r->{_channel} ],
#        event => 'check_error'
#    });
}


sub connected {
    my ( $self, $kernel ) = @_[OBJECT, KERNEL];

    $self->_log(v => 4, msg => 'connected to db');

    return if ($self->{first_connect}++);

    $kernel->call( "$self" => do => {
        sql => 'CREATE TABLE clients (clid VARCHAR(50) PRIMARY KEY, acttime DATE)',
        event => 'check_error'
    });
    
    $kernel->call( "$self" => do => {
        sql => qq(CREATE TRIGGER update_clients AFTER INSERT ON clients
     BEGIN
      UPDATE clients SET acttime = DATETIME('NOW') WHERE clid=new.clid;
     END;),
        event => 'check_error'
    });

    $kernel->call( "$self" => do => {
        sql => 'CREATE TABLE cli_ch_map (clid VARCHAR(50) UNIQUE, channel VARCHAR(255), eid INTEGER)',
        event => 'check_error'
    });
    
    $kernel->call( "$self" => do => {
        sql => 'CREATE TABLE channels (channel VARCHAR(255) PRIMARY KEY, eid INTEGER)',
        event => 'check_error'
    });
    
    # XXX blob?
    $kernel->call( "$self" => do => {
        sql => 'CREATE TABLE events (eid INTEGER PRIMARY KEY, channel VARCHAR(255), event TEXT)',
        event => 'check_error'
    });
    
    $kernel->call( "$self" => do => {
        sql => qq(CREATE TRIGGER insert_events AFTER INSERT ON events
     BEGIN
      UPDATE channels SET eid=new.eid WHERE channel=new.channel;
     END;),
        event => 'check_error'
    });

    $self->add_client( "test", [ "/foo" ] );
}

# methods

sub add_client {
    my ( $self, $clid, $channels ) = @_;
   
    # TODO replace
#    $poe_kernel->call( "$self" => insert => {
#        event => 'check_error',
#        insert => [ map +{
#            clid => $clid,
#            channel => $_
#        }, @$channels ],
#    }) if ( ref $channels ); # array ref
    
    $self->add_channel( $clid, $channels )
        if ( ref $channels );

    $poe_kernel->call( "$self" => do => {
        sql => 'REPLACE INTO clients (clid) VALUES (?)',
        placeholders => [ $clid ],
        event => 'check_error'
    });
}

sub remove_client {
    my ( $self, $clid ) = @_;
    
    $poe_kernel->call( "$self" => do => {
        sql => 'DELETE FROM cli_ch_map WHERE clid=?',
        placeholders => [ $clid ],
        event => 'check_error'
    });

    $poe_kernel->call( "$self" => do => {
        sql => 'DELETE FROM clients WHERE clid=?',
        placeholders => [ $clid ],
        event => 'check_error'
    });
}

sub add_channel {
    my ( $self, $clid, $channel ) = @_;
    $channel = [ $channel ] unless ( ref $channel );

    foreach ( @$channel ) {    
        $poe_kernel->call( "$self" => do => {
            sql => 'REPLACE INTO channels (channel) VALUES (?)',
            placeholders => [ $_ ],
            event => 'check_error'
        });
        $poe_kernel->call( "$self" => do => {
            sql => 'REPLACE INTO cli_ch_map (channel, clid) VALUES (?, ?)',
            placeholders => [ $_, $clid ],
            event => 'check_error'
        });
    }
}

sub remove_channel {
    my ( $self, $clid, $channel ) = @_;
    
    $poe_kernel->call( "$self" => do => {
        sql => 'DELETE FROM cli_ch_map WHERE clid=? AND channel=?',
        placeholders => [ $clid, $channel ],
        event => 'check_error'
    });
}

sub deliver_event {
    # XXX hm, ugly
    my ( $self, $ev ) = $_[ KERNEL ] ? @_[ OBJECT, ARG0 ] : @_;
    
    # XXX ev stringifies into json?
    $poe_kernel->call( "$self" => insert => {
        hash => {
            event => $ev->as_string,
            channel => $ev->channel,
        },
        table => 'events',
        last_insert_id => {},
        event => 'update_eid',
        _channel => $ev->channel
    });
}

1;

