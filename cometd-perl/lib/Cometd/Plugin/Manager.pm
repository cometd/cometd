package Cometd::Plugin::Manager;

use Cometd qw( Plugin Event );
use base 'Cometd::Plugin';

use POE;
use JSON;
use POE::Filter::Line;
use Data::Dumper;

# TODO set a flag
BEGIN {
    eval "use Devel::Gladiator";
};

use strict;
use warnings;

sub new {
    my $class = shift;
    $class->SUPER::new(
        name => 'Manager',
        @_
    );
}

sub as_string {
    __PACKAGE__;
}

# ---------------------------------------------------------
# server

sub local_connected {
    my ( $self, $server, $con, $socket ) = @_;
    
    $self->take_connection( $con );

    # POE::Filter::Stackable object:
    $con->filter->push( POE::Filter::Line->new() );
    
    $con->send( "Cometd Manager - commands: dump [val], list conn, con dump [val], find leaks, find refs, quit" );
    
    # XXX should we pop the stream filter off the top?

    return 1;
}

sub local_receive {
    my ( $self, $server, $con, $data ) = @_;
    
    $self->_log( v => 4, msg => "manager:".Data::Dumper->Dump([ $data ]));
    
    if ( $data =~ m/^help/i ) {
        $con->send( "commands: dump [val], list conn, con dump [val], find leaks, find refs, quit" );
    } elsif ( $data =~ m/^dump (.*)/i ) {
        $con->send( eval "Data::Dumper->Dump([$1])" );
    } elsif ( $data =~ m/^x (.*)/i ) {
        $con->send( eval "$1" );
    } elsif ( $data =~ m/^list conn/i ) {
        foreach my $p (@POE::Component::Cometd::COMPONENTS) {
            next unless ($p);
            foreach my $c (values %{$p->{heaps}}) {
                $con->send( $p->name." - $c - ".$c->{addr} );
            }
        }
        $con->send('done.');
    } elsif ( $data =~ m/^con dump (\S+)/i ) {
        my $id = $1;
        $con->send('looking for '.$id);
        LOOP: foreach my $p (@POE::Component::Cometd::COMPONENTS) {
            next unless ($p);
            foreach my $c (values %{$p->{heaps}}) {
                next unless ( lc( $c->ID ) eq $id );
                $con->send( $p->name." - $c - ".Data::Dumper->Dump([$c]) );
                last LOOP;
            }
        }
    } elsif ( $data =~ m/^find leaks/i ) {
        my $array = Devel::Gladiator::walk_arena();
        foreach my $value (@$array) {
            next unless ( ref($value) =~ m/Cometd\:\:Connection/ );
            my $found = undef;
            foreach my $c (@POE::Component::Cometd::COMPONENTS) {
                next unless ($c);
                $found = $c
                    if (exists( $c->{heaps}->{$value->ID} ));
            }
            if ($found) {
                #$con->send( "cometd connection: ".$value->ID." with plugin ".$value->plugin()." found in ".$found->name );
            } else {
                $con->send( "cometd connection: ".$value->ID." with plugin ".$value->plugin()." not found --- leaked!" );
            }
        }
        $con->send( "done." );
    } elsif ( $data =~ m/^find refs/i ) {
        my $array = Devel::Gladiator::walk_arena();
        foreach my $value (@$array) {
            if ( ref($value) =~ m/Cometd/ && ref($value) !~ m/Cometd::Session/ ) {
                $con->send( "obj: $value ".( $value->can( "name" ) ? $value->name : '' ));
            }
        }
        $con->send( "done." );
    } elsif ( $data =~ m/^devent (\S+) (.*)/i ) {
        my ($ch, $data) = ($1,$2);
        eval {
            $data = ( $data =~ m/^\{/ ) ? jsonToObj( $data ) : { text => $data };
        };
        if ($@) {
            $con->send( "error (event not sent): $@" );
            return;
        }
        my $event = new Cometd::Event( channel => $ch, data => $data );
        $poe_kernel->call( $self->{alias} => deliver_event => $event );
        $con->send( "sent ".$event->as_string );
    } elsif ( $data =~ m/^add channel (\S+) (.*)/i ) {
        my ($clid, $ch) = ($1, $2);
        $poe_kernel->call( $self->{alias} => add_channels => $clid => $ch );
        $con->send( "sent adding $ch to $clid" );
    } elsif ( $data =~ m/^sql (.*)/i ) {
        $poe_kernel->call( $self->{alias} => db_do => $1 => sub {
            $con->send( "response: ".Data::Dumper->Dump([ shift ]) );
        } );
        $con->send( "sent $1 to $self->{alias}" );
    } elsif ( $data =~ m/^(select .*)/i ) {
        $poe_kernel->call( $self->{alias} => db_select => $1 => sub {
            $con->send( "response: ".Data::Dumper->Dump([ shift ]) );
        } );
        $con->send( "sent $1 to $self->{alias}" );
    } elsif ( $data =~ m/^events (\S+)/i ) {
        $poe_kernel->call( $self->{alias} => get_events => $1 => sub {
            $con->send( "response: ".Data::Dumper->Dump([ shift ]) );
        } );
        $con->send( "requesting events for $1" );
    } elsif ( $data =~ m/^quit/i ) {
        $con->send( "goodbye." );
        $con->close();
    }
    
    return 1;
}

1;
