package Sprocket::Session;

use warnings;
use strict;


use POE;
use base qw(POE::Session);
use Errno qw(ENOSYS);

our $VERSION = '0.01';

sub _invoke_state {
  my ($self, $source_session, $state, $etc, $file, $line, $fromstate) = @_;

  # Trace the state invocation if tracing is enabled.

  if ($self->[POE::Session::SE_OPTIONS]->{+POE::Session::OPT_TRACE}) {
    POE::Kernel::_warn(
      $POE::Kernel::poe_kernel->ID_session_to_id($self),
      " -> $state (from $file at $line)\n"
    );
  }

  # The desired destination state doesn't exist in this session.
  # Attempt to redirect the state transition to _default.

  unless (exists $self->[POE::Session::SE_STATES]->{$state}) {

    # There's no _default either; redirection's not happening today.
    # Drop the state transition event on the floor, and optionally
    # make some noise about it.

    unless (exists $self->[POE::Session::SE_STATES]->{+POE::Session::EN_DEFAULT}) {
      $! = ENOSYS;
      if ($self->[POE::Session::SE_OPTIONS]->{+POE::Session::OPT_DEFAULT} and $state ne POE::Session::EN_SIGNAL) {
        my $loggable_self =
          $POE::Kernel::poe_kernel->_data_alias_loggable($self);
        POE::Kernel::_warn(
          "a '$state' event was sent from $file at $line to $loggable_self ",
          "but $loggable_self has neither a handler for it ",
          "nor one for _default\n"
        );
      }
      return undef;
    }

    # If we get this far, then there's a _default state to redirect
    # the transition to.  Trace the redirection.

    if ($self->[POE::Session::SE_OPTIONS]->{+POE::Session::OPT_TRACE}) {
      POE::Kernel::_warn(
        $POE::Kernel::poe_kernel->ID_session_to_id($self),
        " -> $state redirected to _default\n"
      );
    }

    # Transmogrify the original state transition into a corresponding
    # _default invocation.  ARG1 is copied from $etc so it can't be
    # altered from a distance.

    $etc   = [ $state, [@$etc] ];
    $state = POE::Session::EN_DEFAULT;
  }

  # If we get this far, then the state can be invoked.  So invoke it
  # already!

  # Package and object states are invoked this way.
  SWITCH: {
    if ( $state eq POE::Session::EN_DEFAULT
      && ( $etc->[0] =~ m/^([^\|]+)\/(.*)/ ) ) {
      last SWITCH unless ( $1 && $2 );
      
      my ( $heap, $nstate ) = ( $1, $2 );
       
      # does this state exist in this session?
      my $om = $self->[POE::Session::SE_STATES]->{ $nstate };
      unless( $om ) {
        $om = $self->[POE::Session::SE_STATES]->{ POE::Session::EN_DEFAULT };
      }
      last SWITCH unless( $om );
       
      # does this object have this method?
      my ( $object, $method ) = @$om;
      #last SWITCH unless ( $object->can( $method ) );
      if ( $object->{heap} = $object->{heaps}->{ $heap } ) {
        my $ret;
        if ( $method eq POE::Session::EN_DEFAULT ) {
          $method = POE::Session::EN_DEFAULT;
          $ret =
          $object->$method(                 # package/object (implied)
            $self,                          # session
            $POE::Kernel::poe_kernel,       # kernel
            $object->{heap},                # heap
            $nstate,                        # state
            $source_session,                # sender
            undef,                          # unused #6
            $file,                          # caller file name
            $line,                          # caller file line
            $fromstate,                     # caller state
            $nstate,                           # original event
            @{$etc->[1]}                    # args
          );
        } else {
          $ret =
          $object->$method(                 # package/object (implied)
            $self,                          # session
            $POE::Kernel::poe_kernel,       # kernel
            $object->{heap},                # heap
            $nstate,                        # state
            $source_session,                # sender
            undef,                          # unused #6
            $file,                          # caller file name
            $line,                          # caller file line
            $fromstate,                     # caller state
            @{$etc->[1]}                    # args
          );
        }
        $object->{heap} = undef;
    
        return $ret;
      }
    }
  }
  
  # Inline states are invoked this way.

  if (ref($self->[POE::Session::SE_STATES]->{$state}) eq 'CODE') {
    return $self->[POE::Session::SE_STATES]->{$state}->
      ( undef,                          # object
        $self,                          # session
        $POE::Kernel::poe_kernel,       # kernel
        $self->[POE::Session::SE_NAMESPACE],          # heap
        $state,                         # state
        $source_session,                # sender
        undef,                          # unused #6
        $file,                          # caller file name
        $line,                          # caller file line
        $fromstate,                     # caller state
        @$etc                           # args
      );
  }


  my ($object, $method) = @{$self->[POE::Session::SE_STATES]->{$state}};
  return
    $object->$method                    # package/object (implied)
      ( $self,                          # session
        $POE::Kernel::poe_kernel,       # kernel
        $self->[POE::Session::SE_NAMESPACE],          # heap
        $state,                         # state
        $source_session,                # sender
        undef,                          # unused #6
        $file,                          # caller file name
        $line,                          # caller file line
        $fromstate,            # caller state
        @$etc                           # args
      );
}

1;
