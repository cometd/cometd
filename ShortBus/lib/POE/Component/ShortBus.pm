package POE::Component::ShortBus;

use warnings;
use strict;

our $VERSION;
$VERSION = do {my($r)=(q$Revision$=~/(\d+)/);sprintf"1.%04d",$r};

use Carp qw( carp croak );

use Socket qw( INADDR_ANY inet_ntoa );

use POE qw( Wheel::ReadWrite );

# constants
use constant SVR_ADDR_BIND => "server.addr.bind";
use constant SVR_PORT_BIND => "server.port.bind";


sub new {
    my ($class, $params) = @_;

    my $self = bless { }, $class;

    $self->{+SVR_ADDR_BIND} = delete($params->{BindAddress})
        if exists $params->{BindAddress};
    
    $self->{+SVR_PORT_BIND} = delete($params->{BindPort})
        if exists $params->{BindPort};

    $self->{+SVR_ADDR_BIND} ||= inet_ntoa(INADDR_ANY);
    $self->{+SVR_PORT_BIND} ||= 8081;

    $self->_session_start();

    $self->_routers_start();

    $self;
}


sub _mutator {
    my ($self, $field) = @_;
     
    if (@_ == 3) {
        my $new_value = $self->{$field} = $_[2];
        $self->save();
        return $new_value;
    }
     
    return $self->{$field} if @_ == 2;
    croak "Bad number of parameters";
}

sub bind_address {
    my $self = shift;
    return $self->_mutator(SVR_ADDR_BIND, @_);
}

sub bind_port {
    my $self = shift;
    return $self->_mutator(SVR_PORT_BIND, @_);
}


our $AUTOLOAD;
sub AUTOLOAD {
    my $self = shift;
    warn "$self->$AUTOLOAD(@_)";
}

1;

