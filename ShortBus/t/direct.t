use strict;
use warnings;

use Test::More;

plan tests => 39;

use Test::MockObject;

my $mock = Test::MockObject->new;

my $sb;

$POE::Kernel::poe_kernel = $mock;

$mock->fake_module('POE::Kernel',
  'import' => sub {
                my $package = caller();
                no strict 'refs';
                *{ $package . '::poe_kernel'      } = \$mock;
              },
);

use POE::Session;

$mock->set_true('session_alloc');

$mock->set_true('ID_session_to_id');

use_ok('POE::Component::ShortBus');

$sb = POE::Component::ShortBus->new;

isa_ok($sb, 'POE::Component::ShortBus');

is_deeply($sb->{+POE::Component::ShortBus::SB_QUEUES()}, {},
            'Queues initialised to empty hash');

cmp_ok($sb->{+POE::Component::ShortBus::SB_NEXT_ID()}, '==', 0,
         'Queue id initialised to 0');

my @state_args = ();

$state_args[KERNEL] = ($mock);

shift(@state_args); # So we can call it as a method

my $call_test;

$mock->mock(
  'call' => sub {
              is($_[1], $sb, "Alias ok for \$sb->${call_test}");
              is($_[2], $call_test, "Event name ok for \$sb->${call_test}");
              is($_[3], 'foo', "Args passed ok for \$sb->${call_test}");
            },
);

foreach (qw/create post destroy connect disconnect acknowledge shutdown/) {
  $call_test = $_;
  $sb->$call_test('foo');
}

$mock->mock(
  'alias_set' =>
    sub { is($_[1], $sb, 'Alias set to object during start'); }
);

$mock->mock(
  'alias_remove' =>
    sub { is($_[1], $sb, 'Alias removed for object during shutdown'); }
);

$sb->_poe_start(@state_args);

$sb->_poe_shutdown(@state_args);

my @postback_args = ();
my @return_value  = ();

my $pb = sub { @postback_args = @_; };

$state_args[ARG0-1] = $pb;

@return_value = $sb->_poe_create(@state_args);

cmp_ok($postback_args[0], '==', POE::Component::ShortBus::SB_RC_OK(),
        'create ok');

cmp_ok($postback_args[1], '==', 0, 'new id ok');

cmp_ok($sb->{+POE::Component::ShortBus::SB_NEXT_ID()}, '==', 1, 'next id ok');

is_deeply(\@postback_args, \@return_value, 'Postback args returned ok');

my $save = $sb->{+POE::Component::ShortBus::SB_QUEUES()}{0};

is_deeply($save,
  {
    POE::Component::ShortBus::SB_Q_QUEUE(), [],
    POE::Component::ShortBus::SB_Q_EVENT_ID(), 0,
  },
  'Queue setup ok'
);

@postback_args = ();

@return_value = $sb->_poe_destroy(@state_args);

cmp_ok($postback_args[0], '==', POE::Component::ShortBus::SB_RC_ARGS(),
        'destroy args error without queue id ok');

is_deeply(\@postback_args, \@return_value, 'Postback args returned ok');

$state_args[ARG0] = 0;

@postback_args = ();

{
  local $save->{+POE::Component::ShortBus::SB_Q_LISTENER()}
    = sub { cmp_ok($_[0], '==', POE::Component::ShortBus::SB_RC_DESTROY(),
                    'Destroy sent to listener ok'); };

  @return_value = $sb->_poe_destroy(@state_args);

  cmp_ok($postback_args[0], '==', POE::Component::ShortBus::SB_RC_OK(),
          'Destroy returned ok');

  is_deeply(\@postback_args, \@return_value, 'Postback args returned ok');

}

@postback_args = ();

@return_value = $sb->_poe_destroy(@state_args);

cmp_ok($postback_args[0], '==', POE::Component::ShortBus::SB_RC_NOSUCH(),
        'Destroy on invalid queue returned nosuch');

is_deeply(\@postback_args, \@return_value, 'Postback args returned ok');

@postback_args = ();
