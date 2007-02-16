package Cometd::Plugin::Simple;

use Cometd qw( Plugin Event );
use base 'Cometd::Plugin';

use POE;
use POE::Filter::Line;
use Data::Dumper;
use Data::UUID;
use JSON;

use strict;
use warnings;

sub new {
    my $class = shift;
    $class->SUPER::new(
        name => 'Manager',
        uuid => Data::UUID->new(),
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
    
    my $filter = $con->filter;

    # POE::Filter::Stackable object:
    $filter->push( POE::Filter::Line->new() );
    
    $filter->shift(); # POE::Filter::Stream
    
    my $clid = $con->clid( $self->{uuid}->create_str() ); # this contacts event manager
    
    my $channels = [ $self->{default_channel}, $con->{chan} = '/chat' ];
    $con->send( "Your client id: $clid | ".$con->ID." You are on @$channels" );
    
    $con->add_channels( $channels ); # so does this 
        
    $con->send_event( channel => $con->{chan}, data => "joined" );

    return 1;
}

sub local_receive {
    my ( $self, $server, $con, $data ) = @_;
    
    $self->_log( v => 4, msg => Data::Dumper->Dump([ $data ]));
    
    if ( $data =~ m/^quit/i ) {
        $con->send( "goodbye." );
        $con->close();
        $con->send_event( channel => $con->{chan}, data => "disconnected" )
            if ( $con->{chan} );
        $con->remove_client();
    } elsif ( $data =~ m/^join (.*)/ ) {
        my $ch = $1;
        $con->send_event( channel => $con->{chan}, data => "parted" )
            if ( $con->{chan} );
#        $con->remove_channels( [ $con->{chan}] );
        $con->{chan} = $ch;
        $con->add_channels( [ $con->{chan} ] );
        $con->send("added $ch");
        $con->send_event( channel => $con->{chan}, data => "joined" );
    } elsif ( $data =~ m/^part (.*)/ ) {
        my $ch = $1;
        $con->send_event( channel => $con->{chan}, data => "parted" )
            if ( $con->{chan} && $con->{chan} eq $ch );
#        $con->remove_channels( [ $con->{chan}] );
        $con->remove_channels( [ $ch ] );
        $con->send("parted $ch");
        $con->{chan} = undef if ( $con->{chan} eq $ch );
    } elsif ( $data ) {
        if ( $con->{chan} ) {
            #$con->send( $con->clid.":$data" );
            $con->send_event( channel => $con->{chan}, data => $data );
        } else {
            $con->send( "you are not on a channel, rejoin a channel to make it active" );
        }
        #$con->get_events(); # delivered to events_received
    }
    
    return 1;
}

sub local_disconnected {
    my ( $self, $server, $con ) = @_;
    $con->send_event( channel => $con->{chan}, data => "disconnected" )
        if ( $con->{chan} );
    $con->remove_client();
}

sub events_received {
    my ( $self, $server, $con, $events ) = @_;
    # 'eid' => 25047,
    # 'event' => '{"clientId":"06218EF6-B96C-11DB-BEE8-E8271468007A","channel":"/chat","data":"fart"}'
    foreach ( @$events ) {
#        my $foo = jsonToObj( $_->{event} );
#        next if ($foo->{clientId} eq $con->clid);
#        $con->send(($foo->{clientId} ? $foo->{clientId}.':' : '').$foo->{channel}.'>'.$foo->{data});
        $con->send('>'.$_->{event});
    }
#   $con->send( Data::Dumper->Dump([ $events ]) );
}

sub events_ready {
    my ( $self, $server, $con, $cid ) = @_;
#    $con->send('(events ready) '.$cid);
    #warn "cid: $cid";
    $con->get_events();
}

# ---------------------------------------------------------
# client

sub remote_connected {
    my ( $self, $client, $con, $socket ) = @_;
    $self->take_connection( $con );
    # POE::Filter::Stackable object:
    my $filter = $con->filter;

    $filter->push( POE::Filter::Line->new() );
    
    $filter->shift(); # POE::Filter::Stream

    return 1;
}

sub remote_receive {
    my ($self, $client, $con, $event) = @_;
    #$self->_log(v=>4,msg=>'sending '.$event);
    $client->{count}++;
#    if ( $self->{count} % 10 ) {
#        $self->_log(v => 4, msg => 'event to client count '.$self->{count});
#    }
    return;
    $poe_kernel->post( $self->{event_manager} => deliver_events => new Cometd::Event(
            channel => '/chat',
            data => ( "." x 1024 )
        )
    );
    return 1;
}

1;
