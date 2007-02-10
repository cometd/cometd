package POE::Component::EasyDBI::SubProcess;

use strict;
use warnings FATAL => 'all';

# Initialize our version
our $VERSION = (qw($Revision: 1.15 $))[1];

# Use Error.pm's try/catch semantics
use Error qw( :try );

# We pass in data to POE::Filter::Reference
use POE::Filter::Reference;

# We run the actual DB connection here
use DBI;

sub new {
	my ($class, $opts) = @_;
	my $obj = bless($opts, $class);
	$obj->{queue} = [];
	$obj->{ping_timeout} = $obj->{ping_timeout} || 0;
	return $obj;
}

# This is the subroutine that will get executed upon the fork() call by our parent
sub main {
	if ( $^O eq 'MSWin32' ) {
		binmode(STDIN); binmode(STDOUT);
	}
	# Autoflush to avoid weirdness
	#select((select(STDOUT), $| = 1)[0]);
	select(STDOUT); $|++;
	select(STDERR);	$|++;

    $SIG{__WARN__} = 'DEFAULT';
    $SIG{__DIE__} = 'DEFAULT';

	my $self;
	# check for alternate fork
	if ($_[0] == 1) {
		# we need to read in the first
		my $filter = POE::Filter::Reference->new();
		my $opts;
		# get our first option hashref
		while ( sysread( STDIN, my $buffer = '', 1024 ) ) {
			$opts = $filter->get( [ $buffer ] );
			last if (defined $opts);
		}
		$self = __PACKAGE__->new(splice(@{$opts},0,1));
		$self->{filter} = $filter;
		if (@{$opts}) {
			push(@{$self->{queue}},@{$opts});
		}
		undef $filter;
	} else {
		$self = __PACKAGE__->new(shift);
		$self->{filter} = POE::Filter::Reference->new();
	}
	
	$self->{0} = $0 = "$0 ".__PACKAGE__;
	
	$self->{lastpingtime} = time();

	unless (defined($self->{sig_ignore_off})) {
		$SIG{INT} = $SIG{TERM} = $SIG{HUP} = 'IGNORE';
	}

#	if (defined($self->{use_cancel})) {
		# Signal INT causes query cancel
		# XXX disabled for now
		#$SIG{INT} = sub { if ($sth) { $sth->cancel; } };
#	}

	while (!$self->connect()) {	}
	
	$self->pt("connected at ".localtime());

	return if ($self->{done});

	# check for data in queue first
	$self->process();

	if ($self->{done}) {
		$self->pt("disconnected at ".localtime());
		if ($self->{dbh}) {
			$self->{dbh}->disconnect();
		}
		return;
	}

	# listen for commands from our parent
	READ: while ( sysread( STDIN, my $buffer = '', 1024 ) ) {
		# Feed the line into the filter
		# and put the data in the queue
		my $d = $self->{filter}->get( [ $buffer ] );
		push(@{$self->{queue}},@$d) if ($d);
		
		# INPUT STRUCTURE IS:
		# $d->{action}			= SCALAR	->	WHAT WE SHOULD DO
		# $d->{sql}				= SCALAR	->	THE ACTUAL SQL
		# $d->{placeholders}	= ARRAY		->	PLACEHOLDERS WE WILL USE
		# $d->{id}				= SCALAR	->	THE QUERY ID ( FOR PARENT TO KEEP TRACK OF WHAT IS WHAT )
		# $d->{primary_key}		= SCALAR 	->	PRIMARY KEY FOR A HASH OF HASHES
		# $d->{last_insert_id}	= SCALAR|HASH	->	HASH REF OF TABLE AND FIELD OR SCALAR OF A QUERY TO RUN AFTER
		# and others..

		# process all in the queue until a problem occurs or done
		REDO:
		unless ($self->process()) {
			last READ if ($self->{done});
			# oops problem...
			if ($self->{reconnect}) {
				# need to reconnect
				delete $self->{reconnect};
				# keep trying to connect
				while (!$self->connect()) {	}
				# and bail when we are told
				last READ if ($self->{done});
				goto REDO;
			}
		}
	}
	# Arrived here due to error in sysread/etc
	if ($self->{dbh}) {
		$self->{dbh}->disconnect();
		delete $self->{dbh};
	}
	
	# debug
#	require POE::API::Peek;
#	my $p = POE::API::Peek->new();
#	my @sessions = $p->session_list();
#	require Data::Dumper;
#	open(FH,">db.txt");
#	print FH Data::Dumper->Dump([\@sessions]);
#	close(FH);
}

sub pt {
	$0 = shift->{0}.' '.shift;
}

sub connect {
	my $self = shift;
	
	$self->{output} = undef;
	$self->{error} = undef;

	# Actually make the connection
	try {
		$self->{dbh} = DBI->connect(
			# The DSN we just set up
			(map { $self->{$_} } qw( dsn username password )),

			# We set some configuration stuff here
			{
				((ref($self->{options}) eq 'HASH') ? %{$self->{options}} : ()),
				
				# quiet!!
				'PrintError'	=>	0,
				'PrintWarn'		=>	0,

				# Automatically raise errors so we can catch them with try/catch
				'RaiseError'	=>	1,

				# Disable the DBI tracing
				'TraceLevel'	=>	0,
			},
		);

		# Check for undefined-ness
		if (!defined($self->{dbh})) {
			die "Error Connecting to Database: $DBI::errstr";
		}
	} catch Error with {
		$self->output( $self->make_error( 'DBI', shift ) );
	};

	# Catch errors!
	if ($self->{error} && $self->{no_connect_failures}) {
		sleep($self->{reconnect_wait}) if ($self->{reconnect_wait});
		return 0;
	} elsif ($self->{error}) {
		# QUIT
		$self->{done} = 1;
		return 1;
	}

#	if ($self->{dsn} =~ m/SQLite/ && $self->{options}
#		&& ref($self->{options}) eq 'HASH' && $self->{options}->{AutoCommit}) {
#		# TODO error checking
#		$self->db_do({ sql => 'BEGIN', id => -1 });
#		delete $self->{output};
#	}
	
	# send connect notice
	$self->output({ id => 'DBI-CONNECTED' });
	
	return 1;
}

sub process {
	my $self = shift;

	return 0 unless (@{$self->{queue}});
	
	# Process each data structure
	foreach my $input (shift(@{$self->{queue}})) {
		$input->{action} = lc($input->{action});
		
		# Now, we do the actual work depending on what kind of query it was
		if ($input->{action} eq 'exit') {
			# Disconnect!
			$self->{done} = 1;
			return 0;
		}

		my $now = time();
		my $needping = (($self->{ping_timeout} == 0 or $self->{ping_timeout} > 0)
			and (($now - $self->{lastpingtime}) >= $self->{ping_timeout})) ? 1 : 0;
			
		if ($self->{dbh}) {
# Don't work:
#			unless ($self->{dbh}->{Active}) {
#				# put the query back on the stack
#				unshift(@{$self->{queue}},$input);
#				# and reconnect
#				$self->{dbh}->disconnect();
#				$self->{reconnect} = 1;
#				return 0;
#			}
			if ($needping) {
				if (eval{ $self->{dbh}->ping(); }) {
					$self->pt("pinged at ".localtime());
					$self->{lastpingtime} = $now;
				} else {
					# put the query back on the stack
					unshift(@{$self->{queue}},$input);
					# and reconnect
					$self->{dbh}->disconnect();
					$self->{reconnect} = 1;
					return 0;
				}
			}
			#} elsif (!$self->{dbh}) {
		} else {
			#die "Database gone? : $DBI::errstr";
			# put the query back on the stack
			unshift(@{$self->{queue}},$input);
			# and reconnect
			eval { $self->{dbh}->disconnect(); };
			$self->{reconnect} = 1;
			return 0;
		}

		if (defined($self->{no_cache}) && !defined($input->{no_cache})) {
			$input->{no_cache} = $self->{no_cache};
		}

		if (defined($input->{sql})) {
			# remove beginning whitespace
			$input->{sql} =~ s/^\s*//;
		}
		
		if ( $input->{action} =~ m/^(func|commit|rollback|begin_work)$/ ) {
			$input->{method} = $input->{action};
			$self->do_method( $input );
		} elsif ( $input->{action} eq 'method') {
			# Special command to do $dbh->$method->()
			$self->do_method( $input );
		} elsif ( $input->{action} eq 'insert' ) {
			# Fire off the SQL and return success/failure + rows affected and insert id
			$self->db_insert( $input );
		} elsif ( $input->{action} eq 'do' ) {
			# Fire off the SQL and return success/failure + rows affected
			$self->db_do( $input );
		} elsif ( $input->{action} eq 'single' ) {
			# Return a single result
			$self->db_single( $input );
		} elsif ( $input->{action} eq 'quote' ) {
			$self->db_quote( $input );
		} elsif ( $input->{action} eq 'arrayhash' ) {
			# Get many results, then return them all at the same time in a array of hashes
			$self->db_arrayhash( $input );
		} elsif ( $input->{action} eq 'hashhash' ) {
			# Get many results, then return them all at the same time in a hash of hashes
			# on a primary key of course. the columns are returned in the cols key
			$self->db_hashhash( $input );
		} elsif ( $input->{action} eq 'hasharray' ) {
			# Get many results, then return them all at the same time in a hash of arrays
			# on a primary key of course. the columns are returned in the cols key
			$self->db_hasharray( $input );
		} elsif ( $input->{action} eq 'array' ) {
			# Get many results, then return them all at the same time in an array of comma seperated values
			$self->db_array( $input );
		} elsif ( $input->{action} eq 'arrayarray' ) {
			# Get many results, then return them all at the same time in an array of arrays
			$self->db_arrayarray( $input );
		} elsif ( $input->{action} eq 'hash' ) {
			# Get many results, then return them all at the same time in a hash keyed off the 
			# on a primary key
			$self->db_hash( $input );
		} elsif ( $input->{action} eq 'keyvalhash' ) {
			# Get many results, then return them all at the same time in a hash with
			# the first column being the key and the second being the value
			$self->db_keyvalhash( $input );
		} else {
			# Unrecognized action!
			$self->{output} = $self->make_error( $input->{id}, "Unknown action sent '$input->{id}'" );
		}
		# XXX another way?
		if ($input->{id} eq 'DBI' || ($self->{output}->{error}
			&& ($self->{output}->{error} =~ m/no connection to the server/i
			|| $self->{output}->{error} =~ m/server has gone away/i
			|| $self->{output}->{error} =~ m/server closed the connection/i
			|| $self->{output}->{error} =~ m/connect failed/i))) {
			
			unshift(@{$self->{queue}},$input);
			eval { $self->{dbh}->disconnect(); };
			$self->{reconnect} = 1;
			return 0;
		}
		$self->output;
	}
	return 1;
}

sub commit {
	my $self = shift;
	my $id = shift->{id};
	try {
		$self->{dbh}->commit;
	} catch Error with {
		$self->{output} = $self->make_error( $id, shift );
	};
	return ($self->{output}) ? 0 : 1;
}

sub begin_work {
	my $self = shift;
	my $id = shift->{id};
	try {
		$self->{dbh}->begin_work;
	} catch Error with {
		$self->{output} = $self->make_error( $id, shift );
	};
	return ($self->{output}) ? 0 : 1;
}

# This subroutine makes a generic error structure
sub make_error {
	my $self = shift;
	
	# Make the structure
	my $data = { id => shift };

	# Get the error, and stringify it in case of Error::Simple objects
	my $error = shift;

	if (ref($error) && ref($error) eq 'Error::Simple') {
		$data->{error} = $error->text;
	} else {
		$data->{error} = $error;
	}

	if ($data->{error} =~ m/has gone away/i || $data->{error} =~ m/lost connection/i) {
		$data->{id} = 'DBI';
	}

	$self->{error} = $data;

	# All done!
	return $data;
}

# This subroute is for supporting any type of $dbh->$method->() calls
sub do_method {
	# Get the dbi handle
	my $self = shift;

	# Get the input structure
	my $data = shift;

	# The result
	my $result = undef;
	
	my $method = $data->{method};
	my $dbh = $self->{dbh};

	SWITCH: {
	
		if ($data->{begin_work}) {
			$self->begin_work($data) or last SWITCH;
		}

		# Catch any errors
		try {
			if ($data->{args} && ref($data->{args}) eq 'ARRAY') {
				$result = $dbh->$method(@{$data->{args}});
			} else {
				$result = $dbh->$method();
			}
			
		} catch Error with {
			$self->{output} = $self->make_error( $data->{id}, shift );
		};
		
	}
	
	# Check if we got any errors
	if (!defined($self->{output})) {
		# Make output include the results
		$self->{output} = { result => $result, id => $data->{id} };
	}
	
	return;
}

# This subroutine does a DB QUOTE
sub db_quote {
	my $self = shift;
	
	# Get the input structure
	my $data = shift;

	# The result
	my $quoted = undef;

	# Quote it!
	try {
		$quoted = $self->{dbh}->quote( $data->{sql} );
	} catch Error with {
		$self->{output} = $self->make_error( $data->{id}, shift );
	};

	# Check for errors
	if (!defined($self->{output})) {
		# Make output include the results
		$self->{output} = { result => $quoted, id => $data->{id} };
	}
	return;
}

# This subroutine runs a 'SELECT ... LIMIT 1' style query on the db
sub db_single {
	# Get the dbi handle
	my $self = shift;

	# Get the input structure
	my $data = shift;

	# Variables we use
	my $sth = undef;
	my $result = undef;

	# Check if this is a non-select statement
#	if ( $data->{sql} !~ /^SELECT/i ) {
#		$self->{output} = $self->make_error( $data->{id}, "SINGLE is for SELECT queries only! ( $data->{sql} )" );
#		return;
#	}

	SWITCH: {
		if ($data->{begin_work}) {
			$self->begin_work($data) or last SWITCH;
		}
		
		# Catch any errors
		try {
			# Make a new statement handler and prepare the query
			if ($data->{no_cache}) {
				$sth = $self->{dbh}->prepare( $data->{sql} );
			} else {
				# We use the prepare_cached method in hopes of hitting a cached one...
				$sth = $self->{dbh}->prepare_cached( $data->{sql} );
			}

			# Check for undef'ness
			if (!defined($sth)) {
				die 'Did not get a statement handler';
			} else {
				# Execute the query
				try {
                    $sth->execute( @{ $data->{placeholders} } );
				} catch Error with {
					die (defined($sth->errstr)) ? $sth->errstr : $@;
				};
				if (defined($self->{dbh}->errstr)) { die $self->{dbh}->errstr; }
			}
	
			# Actually do the query!
			try {
				# There are warnings when joining a NULL field, which is undef
				no warnings;
				if (exists($data->{separator})) {
					$result = join($data->{separator},$sth->fetchrow_array());
				} else {
					$result = $sth->fetchrow_array();
				}		
			} catch Error with {
				die $sth->errstr;
			};
		
			if (defined($self->{dbh}->errstr)) { die $self->{dbh}->errstr; }
		} catch Error with {
			$self->{output} = $self->make_error( $data->{id}, shift );
		};
	}

	# Check if we got any errors
	if (!defined($self->{output})) {
		# Make output include the results
		$self->{output} = { result => $result, id => $data->{id} };
	}

	# Finally, we clean up this statement handle
	if (defined($sth)) {
		$sth->finish();
	}

	return;
}

# This subroutine does an insert into the db
sub db_insert {
	# Get the dbi handle
	my $self = shift;

	# Get the input structure
	my $data = shift;

	my $dsn = $self->{dsn} || '';

	# Variables we use
	my $sth = undef;
	my $rows = undef;

	my @queries;
	my @placeholders;
	
	# XXX depricate hash for insert
	if (defined($data->{hash}) && !defined($data->{insert})) {
		$data->{insert} = delete $data->{hash};
	}
		
	if (defined($data->{insert}) && ref($data->{insert}) eq 'HASH') {
		$data->{insert} = [$data->{insert}];
	}
	
	# Check if this is a non-insert statement
	if (defined($data->{insert}) && ref($data->{insert}) eq 'ARRAY') {
		delete $data->{placeholders};
		delete $data->{sql};
		foreach my $hash (@{$data->{insert}}) {
			# sort so we always get a consistant list of fields in the errors and placeholders
			my @fields = sort keys %{$hash};
			# adjust the placeholders, they should know that placeholders passed in are irrelevant
			# XXX subtypes when a hash value is a HASH or ARRAY?
			push(@placeholders,[ map { $hash->{$_} } @fields ]);
			push(@queries,"INSERT INTO $data->{table} ("
				.join(',',@fields).") VALUES (".join(',',(map { '?' } @fields)).")");
		}
	} elsif (!defined($data->{sql}) || $data->{sql} !~ /^INSERT/i ) {
		$self->{output} = $self->make_error( $data->{id}, "INSERT is for INSERTS only! ( $data->{sql} )" );
		return;
	} else {
		push(@queries,$data->{sql});
		push(@placeholders,$data->{placeholders});
	}

	for my $i ( 0 .. $#queries ) {
		$data->{sql} = $queries[$i];
		$data->{placeholders} = $placeholders[$i];
		my $do_last = 0;
		
		if ($data->{begin_work} && $i == 0) {
			$self->begin_work($data) or last;
		}
		
		# Catch any errors
		try {
			# Make a new statement handler and prepare the query
			if ($data->{no_cache}) {
				$sth = $self->{dbh}->prepare( $data->{sql} );
			} else {
				# We use the prepare_cached method in hopes of hitting a cached one...
				$sth = $self->{dbh}->prepare_cached( $data->{sql} );
			}
	
			# Check for undef'ness
			if (!defined($sth)) {
				die 'Did not get a statement handler';
			} else {
				# Execute the query
				try {
                    $rows += $sth->execute( @{ $data->{placeholders} } );
				} catch Error with {
					if (defined($sth->errstr)) {
						die $sth->errstr;
					} else {
						die "error when trying to execute bind of placeholders in insert: $_[0]";
					}
				};
				if (defined($self->{dbh}->errstr)) { die $self->{dbh}->errstr; }
			}
		} catch Error with {
			my $e = shift;
			$self->{output} = $self->make_error( $data->{id}, "failed at query #$i : $e" );
			$do_last = 1 unless ($data->{complete_request}); # can't use last here
		};
		last if ($do_last);
	}

	if ($data->{commit} && defined($rows) && !defined($self->{output})) {
		$self->commit($data);
	}

	# If rows is not undef, that means we were successful
	if (defined($rows) && !defined($self->{output})) {
		# Make the data structure
		$self->{output} = { rows => $rows, result => $rows, id => $data->{id} };
		
		unless ($data->{last_insert_id}) {
			if (defined($sth)) {
				$sth->finish();
			}
			return;
		}
		# get the last insert id
		try {
			my $qry = '';
			if (ref($data->{last_insert_id}) eq 'HASH') {
				my $l = $data->{last_insert_id};
				# checks for different database types
				if ($dsn =~ m/dbi:pg/i) {
					$qry = "SELECT $l->{field} FROM $l->{table} WHERE oid='".$sth->{'pg_oid_status'}."'";
				} elsif ($dsn =~ m/dbi:mysql/i) {
					if (defined($self->{dbh}->{'mysql_insertid'})) {
						$self->{output}->{insert_id} = $self->{dbh}->{'mysql_insertid'};
					} else {
						$qry = 'SELECT LAST_INSERT_ID()';
					}
				} elsif ($dsn =~ m/dbi:oracle/i) {
					$qry = "SELECT $l->{field} FROM $l->{table}";
                } elsif ($dsn =~ /dbi:sqlite/i) {
                    $self->{output}->{insert_id} = $self->{dbh}->func('last_insert_rowid');
				} else {
					die "EasyDBI doesn't know how to handle a last_insert_id with your dbi, contact the author.";
				}
			} else {
				# they are supplying thier own query
				$qry = $data->{last_insert_id};
			}
            
			if (defined($sth)) {
				$sth->finish();
			}
            
            if ($qry) {
    			try {
	    			$self->{output}->{insert_id} = $self->{dbh}->selectrow_array($qry);
		    	} catch Error with {
			    	die $sth->error;
    			};
			
	    		if (defined($self->{dbh}->errstr)) { die $self->{dbh}->errstr; }
            }
		} catch Error with {
			# special case, insert was ok, but last_insert_id errored
			$self->{output}->{error} = shift;
		};
	} elsif (!defined($rows) && !defined($self->{output})) {
		# Internal error...
		$self->{output} = $self->make_error( $data->{id}, 'Internal Error in db_do of EasyDBI Subprocess' );
		#die 'Internal Error in db_do';
	}

	# Finally, we clean up this statement handle
	if (defined($sth)) {
		$sth->finish();
	}
	
	return;
}

# This subroutine runs a 'DO' style query on the db
sub db_do {
	# Get the dbi handle
	my $self = shift;

	# Get the input structure
	my $data = shift;

	# Variables we use
	my $sth = undef;
	my $rows = undef;

	# Check if this is a non-select statement
#	if ( $data->{sql} =~ /^SELECT/i ) {
#		$self->{output} = $self->make_error( $data->{id}, "DO is for non-SELECT queries only! ( $data->{sql} )" );
#		return;
#	}

	SWITCH: {
	
		if ($data->{begin_work}) {
			$self->begin_work($data) or last SWITCH;
		}
		
		# Catch any errors
		try {
			# Make a new statement handler and prepare the query
			if ($data->{no_cache}) {
				$sth = $self->{dbh}->prepare( $data->{sql} );
			} else {
				# We use the prepare_cached method in hopes of hitting a cached one...
				$sth = $self->{dbh}->prepare_cached( $data->{sql} );
			}
	
			# Check for undef'ness
			if (!defined($sth)) {
				die 'Did not get a statement handler';
			} else {
				# Execute the query
				try {
                    $rows += $sth->execute( @{ $data->{placeholders} } );
				} catch Error with {
					die (defined($sth->errstr)) ? $sth->errstr : $@;
				};
				if (defined($self->{dbh}->errstr)) { die $self->{dbh}->errstr; }
			}
		} catch Error with {
			$self->{output} = $self->make_error( $data->{id}, shift );
		};

	}

	if ($data->{commit} && defined($rows) && !defined($self->{output})) {
		$self->commit($data);
	}

	# If rows is not undef, that means we were successful
	if (defined($rows) && !defined($self->{output})) {
		# Make the data structure
		$self->{output} = { rows => $rows, result => $rows, id => $data->{id} };
	} elsif (!defined($rows) && !defined($self->{output})) {
		# Internal error...
		$self->{output} = $self->make_error( $data->{id}, 'Internal Error in db_do of EasyDBI Subprocess' );
		#die 'Internal Error in db_do';
	}

	# Finally, we clean up this statement handle
	if (defined($sth)) {
		$sth->finish();
	}
	
	return;
}

sub db_arrayhash {
	# Get the dbi handle
	my $self = shift;

	# Get the input structure
	my $data = shift;

	# Variables we use
	my $sth = undef;
	my $result = [];
	my $rows = 0;

	# Check if this is a non-select statement
#	if ( $data->{sql} !~ /^SELECT/i ) {
#		$self->{output} = $self->make_error( $data->{id}, "ARRAYHASH is for SELECT queries only! ( $data->{sql} )" );
#		return;
#	}

	SWITCH: {

		if ($data->{begin_work}) {
			$self->begin_work($data) or last SWITCH;
		}

		# Catch any errors
		try {
			# Make a new statement handler and prepare the query
			if ($data->{no_cache}) {
				$sth = $self->{dbh}->prepare( $data->{sql} );
			} else {
				# We use the prepare_cached method in hopes of hitting a cached one...
				$sth = $self->{dbh}->prepare_cached( $data->{sql} );
			}
			
			# Check for undef'ness
			if (!defined($sth)) {
				die 'Did not get a statement handler';
			} else {
				# Execute the query
				try {
                    $sth->execute( @{ $data->{placeholders} } );
				} catch Error with {
					die (defined($sth->errstr)) ? $sth->errstr : $@;
				};
				if (defined($self->{dbh}->errstr)) { die $self->{dbh}->errstr; }
			}
	
	#		my $newdata;
	#
	#		# Bind the columns
	#		try {
	#			$sth->bind_columns( \( @$newdata{ @{ $sth->{'NAME_lc'} } } ) );
	#		} catch Error with {
	#			die $sth->errstr;
	#		};
	
			# Actually do the query!
			try {
				while ( my $hash = $sth->fetchrow_hashref() ) {
					if (exists($data->{chunked}) && defined($self->{output})) {
						# chunk results ready to send
						$self->output();
						$result = [];
						$rows = 0;
					}
					$rows++;
					# Copy the data, and push it into the array
					push( @{ $result }, { %{ $hash } } );
					if (exists($data->{chunked}) && $data->{chunked} == $rows) {
						# Make output include the results
						$self->{output} = { rows => $rows, id => $data->{id}, result => $result, chunked => $data->{chunked} };
					}
				}
				# in the case that our rows == chunk
				$self->{output} = undef;
	
			} catch Error with {
				die $sth->errstr;
			};
		
			# XXX is dbh->err the same as sth->err?
			if (defined($self->{dbh}->errstr)) { die $self->{dbh}->errstr; }

			# Check for any errors that might have terminated the loop early
			if ( $sth->err() ) {
				# Premature termination!
				die $sth->errstr;
			}
		} catch Error with {
			$self->{output} = $self->make_error( $data->{id}, shift );
		};
		
	}
	
	# Check if we got any errors
	if (!defined($self->{output})) {
		# Make output include the results
		$self->{output} = { rows => $rows, id => $data->{id}, result => $result };
		if (exists($data->{chunked})) {
			$self->{output}->{last_chunk} = 1;
			$self->{output}->{chunked} = $data->{chunked};
		}
	}

	# Finally, we clean up this statement handle
	if (defined($sth)) {
		$sth->finish();
	}
	
	return;
}

sub db_hashhash {
	# Get the dbi handle
	my $self = shift;

	# Get the input structure
	my $data = shift;

	# Variables we use
	my $sth = undef;
	my $result = {};
    my $rows = 0;

	# Check if this is a non-select statement
#	if ( $data->{sql} !~ /^SELECT/i ) {
#		$self->{output} = $self->make_error( $data->{id}, "HASHHASH is for SELECT queries only! ( $data->{sql} )" );
#		return;
#	}

	my (@cols, %col);
	
	SWITCH: {

		if ($data->{begin_work}) {
			$self->begin_work($data) or last SWITCH;
		}
		
		# Catch any errors
		try {
			# Make a new statement handler and prepare the query
			if ($data->{no_cache}) {
				$sth = $self->{dbh}->prepare( $data->{sql} );
			} else {
				# We use the prepare_cached method in hopes of hitting a cached one...
				$sth = $self->{dbh}->prepare_cached( $data->{sql} );
			}
	
			# Check for undef'ness
			if (!defined($sth)) {
				die 'Did not get a statement handler';
			} else {
				# Execute the query
				try {
                    $sth->execute( @{ $data->{placeholders} } );
				} catch Error with {
					die (defined($sth->errstr)) ? $sth->errstr : $@;
				};
				if (defined($self->{dbh}->errstr)) { die $self->{dbh}->errstr; }
			}
	
			# The result hash
			my $newdata = {};
	
			# Check the primary key
			my $foundprimary = 0;
	
			# default to the first one
			unless (defined($data->{primary_key})) {
				$data->{primary_key} = 1;
			}
	
			if ($data->{primary_key} =~ m/^\d+$/) {
				# primary_key can be a 1 based index
				if ($data->{primary_key} > $sth->{NUM_OF_FIELDS}) {
	#				die "primary_key ($data->{primary_key}) is out of bounds (".$sth->{NUM_OF_FIELDS}.")";
					die "primary_key ($data->{primary_key}) is out of bounds";
				}
				
				$data->{primary_key} = $sth->{NAME}->[($data->{primary_key}-1)];
			}
			
			# Find the column names
			for my $i ( 0 .. $sth->{NUM_OF_FIELDS}-1 ) {
				$col{$sth->{NAME}->[$i]} = $i;
				push(@cols, $sth->{NAME}->[$i]);
				$foundprimary = 1 if ($sth->{NAME}->[$i] eq $data->{primary_key});
			}
			
			unless ($foundprimary == 1) {
				die "primary key ($data->{primary_key}) not found";
			}
			
			# Actually do the query!
			try {
				while ( my @row = $sth->fetchrow_array() ) {
					if (exists($data->{chunked}) && defined($self->{output})) {
						# chunk results ready to send
						$self->output();
						$result = {};
						$rows = 0;
					}
					$rows++;
					foreach (@cols) {
						$result->{$row[$col{$data->{primary_key}}]}{$_} = $row[$col{$_}];
					}
					if (exists($data->{chunked}) && $data->{chunked} == $rows) {
						# Make output include the results
						$self->{output} = {
                            rows => $rows,
                            result => $result,
                            id => $data->{id},
                            cols => [ @cols ],
                            chunked => $data->{chunked},
                            primary_key => $data->{primary_key}
                        };
					}
				}
				# in the case that our rows == chunk
				$self->{output} = undef;
				
			} catch Error with {
				die $sth->errstr;
			};
			
			if (defined($self->{dbh}->errstr)) { die $self->{dbh}->errstr; }
	
			# Check for any errors that might have terminated the loop early
			if ( $sth->err() ) {
				# Premature termination!
				die $sth->errstr;
			}
		} catch Error with {
			$self->{output} = $self->make_error( $data->{id}, shift );
		};
		
	}

	# Check if we got any errors
	if (!defined($self->{output})) {
		# Make output include the results
		$self->{output} = { rows => $rows, id => $data->{id}, result => $result, cols => [ @cols ], primary_key => $data->{primary_key} };
		if (exists($data->{chunked})) {
			$self->{output}->{last_chunk} = 1;
			$self->{output}->{chunked} = $data->{chunked};
		}
	}

	# Finally, we clean up this statement handle
	if (defined($sth)) {
		$sth->finish();
	}
	
	return;
}

sub db_hasharray {
	# Get the dbi handle
	my $self = shift;

	# Get the input structure
	my $data = shift;

	# Variables we use
	my $sth = undef;
	my $result = {};
    my $rows = 0;

	# Check if this is a non-select statement
#	if ( $data->{sql} !~ /^SELECT/i ) {
#		$self->{output} = $self->make_error( $data->{id}, "HASHARRAY is for SELECT queries only! ( $data->{sql} )" );
#		return;
#	}

	my (@cols, %col);
	
	SWITCH: {

		if ($data->{begin_work}) {
			$self->begin_work($data) or last SWITCH;
		}
		
		# Catch any errors
		try {
			# Make a new statement handler and prepare the query
			if ($data->{no_cache}) {
				$sth = $self->{dbh}->prepare( $data->{sql} );
			} else {
				# We use the prepare_cached method in hopes of hitting a cached one...
				$sth = $self->{dbh}->prepare_cached( $data->{sql} );
			}
	
			# Check for undef'ness
			if (!defined($sth)) {
				die 'Did not get a statement handler';
			} else {
				# Execute the query
				try {
                    $sth->execute( @{ $data->{placeholders} } );
				} catch Error with {
					die (defined($sth->errstr)) ? $sth->errstr : $@;
				};
				if (defined($self->{dbh}->errstr)) { die $self->{dbh}->errstr; }
			}

			# The result hash
			my $newdata = {};
	
			# Check the primary key
			my $foundprimary = 0;

			if ($data->{primary_key} =~ m/^\d+$/) {
				# primary_key can be a 1 based index
				if ($data->{primary_key} > $sth->{NUM_OF_FIELDS}) {
#					die "primary_key ($data->{primary_key}) is out of bounds (".$sth->{NUM_OF_FIELDS}.")";
					die "primary_key ($data->{primary_key}) is out of bounds";
				}
				
				$data->{primary_key} = $sth->{NAME}->[($data->{primary_key}-1)];
			}
		
			# Find the column names
			for my $i ( 0 .. $sth->{NUM_OF_FIELDS}-1 ) {
				$col{$sth->{NAME}->[$i]} = $i;
				push(@cols, $sth->{NAME}->[$i]);
				$foundprimary = 1 if ($sth->{NAME}->[$i] eq $data->{primary_key});
			}
		
			unless ($foundprimary == 1) {
				die "primary key ($data->{primary_key}) not found";
			}
		
			# Actually do the query!
			try {
				while ( my @row = $sth->fetchrow_array() ) {
					if (exists($data->{chunked}) && defined($self->{output})) {
						# chunk results ready to send
						$self->output();
						$result = {};
						$rows = 0;
					}
					$rows++;
					push(@{ $result->{$row[$col{$data->{primary_key}}]} }, @row);
					if (exists($data->{chunked}) && $data->{chunked} == $rows) {
						# Make output include the results
						$self->{output} = { rows => $rows, result => $result, id => $data->{id}, cols => [ @cols ], chunked => $data->{chunked}, primary_key => $data->{primary_key} };
					}
				}
				# in the case that our rows == chunk
				$self->{output} = undef;
			
			} catch Error with {
				die $sth->errstr;
			};
		
			if (defined($self->{dbh}->errstr)) { die $self->{dbh}->errstr; }
	
			# Check for any errors that might have terminated the loop early
			if ( $sth->err() ) {
				# Premature termination!
				die $sth->errstr;
			}
		} catch Error with {
			$self->{output} = $self->make_error( $data->{id}, shift );
		};
		
	}
	
	# Check if we got any errors
	if (!defined($self->{output})) {
		# Make output include the results
		$self->{output} = { rows => $rows, result => $result, id => $data->{id}, cols => [ @cols ], primary_key => $data->{primary_key} };
		if (exists($data->{chunked})) {
			$self->{output}->{last_chunk} = 1;
			$self->{output}->{chunked} = $data->{chunked};
		}
	}

	# Finally, we clean up this statement handle
	if (defined($sth)) {
		$sth->finish();
	}
	
	return;
}

sub db_array {
	# Get the dbi handle
	my $self = shift;

	# Get the input structure
	my $data = shift;

	# Variables we use
	my $sth = undef;
	my $result = [];
    my $rows = 0;

	# Check if this is a non-select statement
#	if ( $data->{sql} !~ /^SELECT/i ) {
#		$self->{output} = $self->make_error( $data->{id}, "ARRAY is for SELECT queries only! ( $data->{sql} )" );
#		return;
#	}

	SWITCH: {

		if ($data->{begin_work}) {
			$self->begin_work($data) or last SWITCH;
		}
		
		# Catch any errors
		try {
			# Make a new statement handler and prepare the query
			if ($data->{no_cache}) {
				$sth = $self->{dbh}->prepare( $data->{sql} );
			} else {
				# We use the prepare_cached method in hopes of hitting a cached one...
				$sth = $self->{dbh}->prepare_cached( $data->{sql} );
			}

			# Check for undef'ness
			if (!defined($sth)) {
				die 'Did not get a statement handler';
			} else {
				# Execute the query
				try {
                    $sth->execute( @{ $data->{placeholders} } );
				} catch Error with {
					die (defined($sth->errstr)) ? $sth->errstr : $@;
				};
				if (defined($self->{dbh}->errstr)) { die $self->{dbh}->errstr; }
			}
	
			# The result hash
			my $newdata = {};
			
			# Actually do the query!
			try {
			    # There are warnings when joining a NULL field, which is undef
				no warnings;

				while ( my @row = $sth->fetchrow_array() ) {
					if (exists($data->{chunked}) && defined($self->{output})) {
						# chunk results ready to send
						$self->output();
						$result = [];
						$rows = 0;
					}
					$rows++;
					if (exists($data->{separator})) {
						push(@{$result},join($data->{separator},@row));
					} else {
						push(@{$result},join(',',@row));
					}
					if (exists($data->{chunked}) && $data->{chunked} == $rows) {
						# Make output include the results
						$self->{output} = { rows => $rows, result => $result, id => $data->{id}, chunked => $data->{chunked} };
					}
				}
				# in the case that our rows == chunk
				$self->{output} = undef;
				
			} catch Error with {
				die $!;
				#die $sth->errstr;
			};
			
			if (defined($self->{dbh}->errstr)) { die $self->{dbh}->errstr; }
	
			# Check for any errors that might have terminated the loop early
			if ( $sth->err() ) {
				# Premature termination!
				die $sth->errstr;
			}
		} catch Error with {
			$self->{output} = $self->make_error( $data->{id}, shift );
		};

	}
	
	# Check if we got any errors
	if (!defined($self->{output})) {
		# Make output include the results
		$self->{output} = { rows => $rows, result => $result, id => $data->{id} };
		if (exists($data->{chunked})) {
			$self->{output}->{last_chunk} = 1;
			$self->{output}->{chunked} = $data->{chunked};
		}
	}

	# Finally, we clean up this statement handle
	if (defined($sth)) {
		$sth->finish();
	}
	
	return;
}

sub db_arrayarray {
	# Get the dbi handle
	my $self = shift;

	# Get the input structure
	my $data = shift;

	# Variables we use
	my $sth = undef;
	my $result = [];
    my $rows = 0;

	# Check if this is a non-select statement
#	if ( $data->{sql} !~ /^SELECT/i ) {
#		$self->{output} = $self->make_error( $data->{id}, "ARRAYARRAY is for SELECT queries only! ( $data->{sql} )" );
#		return;
#	}

	SWITCH: {

		if ($data->{begin_work}) {
			$self->begin_work($data) or last SWITCH;
		}

		# Catch any errors
		try {
			# Make a new statement handler and prepare the query
			if ($data->{no_cache}) {
				$sth = $self->{dbh}->prepare( $data->{sql} );
			} else {
				# We use the prepare_cached method in hopes of hitting a cached one...
				$sth = $self->{dbh}->prepare_cached( $data->{sql} );
			}
	
			# Check for undef'ness
			if (!defined($sth)) {
				die 'Did not get a statement handler';
			} else {
				# Execute the query
				try {
                    $sth->execute( @{ $data->{placeholders} } );
				} catch Error with {
					die (defined($sth->errstr)) ? $sth->errstr : $@;
				};
				if (defined($self->{dbh}->errstr)) { die $self->{dbh}->errstr; }
			}
	
			# The result hash
			my $newdata = {};
			
			# Actually do the query!
			try {
				while ( my @row = $sth->fetchrow_array() ) {
					if (exists($data->{chunked}) && defined($self->{output})) {
						# chunk results ready to send
						$self->output();
						$result = [];
						$rows = 0;
					}
					$rows++;
					# There are warnings when joining a NULL field, which is undef
					push(@{$result},\@row);
					if (exists($data->{chunked}) && $data->{chunked} == $rows) {
						# Make output include the results
						$self->{output} = { rows => $rows, result => $result, id => $data->{id}, chunked => $data->{chunked} };
					}
				}
				# in the case that our rows == chunk
				$self->{output} = undef;
				
			} catch Error with {
				die $!;
				#die $sth->errstr;
			};
			
			if (defined($self->{dbh}->errstr)) { die $self->{dbh}->errstr; }
	
			# Check for any errors that might have terminated the loop early
			if ( $sth->err() ) {
				# Premature termination!
				die $sth->errstr;
			}
		} catch Error with {
			$self->{output} = $self->make_error( $data->{id}, shift );
		};
		
	}
	

	# Check if we got any errors
	if (!defined($self->{output})) {
		# Make output include the results
		$self->{output} = { rows => $rows, result => $result, id => $data->{id} };
		if (exists($data->{chunked})) {
			$self->{output}->{last_chunk} = 1;
			$self->{output}->{chunked} = $data->{chunked};
		}
	}

	# Finally, we clean up this statement handle
	if (defined($sth)) {
		$sth->finish();
	}
	
	return;
}

sub db_hash {
	# Get the dbi handle
	my $self = shift;

	# Get the input structure
	my $data = shift;

	# Variables we use
	my $sth = undef;
	my $result = {};
    my $rows = 0;

	# Check if this is a non-select statement
#	if ( $data->{sql} !~ /^SELECT/i ) {
#		$self->{output} = $self->make_error( $data->{id}, "HASH is for SELECT queries only! ( $data->{sql} )" );
#		return;
#	}

	SWITCH: {

		if ($data->{begin_work}) {
			$self->begin_work($data) or last SWITCH;
		}

		# Catch any errors
		try {
			# Make a new statement handler and prepare the query
			if ($data->{no_cache}) {
				$sth = $self->{dbh}->prepare( $data->{sql} );
			} else {
				# We use the prepare_cached method in hopes of hitting a cached one...
				$sth = $self->{dbh}->prepare_cached( $data->{sql} );
			}
	
			# Check for undef'ness
			if (!defined($sth)) {
				die 'Did not get a statement handler';
			} else {
				# Execute the query
				try {
                    $sth->execute( @{ $data->{placeholders} } );
				} catch Error with {
					die (defined($sth->errstr)) ? $sth->errstr : $@;
				};
				if (defined($self->{dbh}->errstr)) { die $self->{dbh}->errstr; }
			}
	
			# The result hash
			my $newdata = {};
			
			# Actually do the query!
			try {
	
				my @row = $sth->fetchrow_array();
				
				if (@row) {
                    $rows = @row;
					for my $i ( 0 .. $sth->{NUM_OF_FIELDS}-1 ) {
						$result->{$sth->{NAME}->[$i]} = $row[$i];
					}
				}
				
			} catch Error with {
				die $sth->errstr;
			};
			
			if (defined($self->{dbh}->errstr)) { die $self->{dbh}->errstr; }
	
			# Check for any errors that might have terminated the loop early
			if ( $sth->err() ) {
				# Premature termination!
				die $sth->errstr;
			}
		} catch Error with {
			$self->{output} = $self->make_error( $data->{id}, shift );
		};
		
	}

	# Check if we got any errors
	if (!defined($self->{output})) {
		# Make output include the results
		$self->{output} = { rows => $rows, result => $result, id => $data->{id} };
	}

	# Finally, we clean up this statement handle
	if (defined($sth)) {
		$sth->finish();
	}

	return;
}

sub db_keyvalhash {
	# Get the dbi handle
	my $self = shift;

	# Get the input structure
	my $data = shift;

	# Variables we use
	my $sth = undef;
	my $result = {};
    my $rows = 0;

	# Check if this is a non-select statement
#	if ( $data->{sql} !~ /^SELECT/i ) {
#		$self->{output} = $self->make_error( $data->{id}, "KEYVALHASH is for SELECT queries only! ( $data->{sql} )" );
#		return;
#	}

	SWITCH: {

		if ($data->{begin_work}) {
			$self->begin_work($data) or last SWITCH;
		}
		
		# Catch any errors
		try {
			# Make a new statement handler and prepare the query
			if ($data->{no_cache}) {
				$sth = $self->{dbh}->prepare( $data->{sql} );
			} else {
				# We use the prepare_cached method in hopes of hitting a cached one...
				$sth = $self->{dbh}->prepare_cached( $data->{sql} );
			}
	
			# Check for undef'ness
			if (!defined($sth)) {
				die 'Did not get a statement handler';
			} else {
				# Execute the query
				try {
                    $sth->execute( @{ $data->{placeholders} } );
				} catch Error with {
					die (defined($sth->errstr)) ? $sth->errstr : $@;
				};
				if (defined($self->{dbh}->errstr)) { die $self->{dbh}->errstr; }
			}
	
			# Actually do the query!
			try {
				while (my @row = $sth->fetchrow_array()) {
					if ($#row < 1) {
						die 'You need at least 2 columns selected for a keyvalhash query';
					}
					if (exists($data->{chunked}) && defined($self->{output})) {
						# chunk results ready to send
						$self->output();
						$result = {};
						$rows = 0;
					}
					$rows++;
					$result->{$row[0]} = $row[1];
					if (exists($data->{chunked}) && $data->{chunked} == $rows) {
						# Make output include the results
						$self->{output} = { rows => $rows, result => $result, id => $data->{id}, chunked => $data->{chunked} };
					}
				}
				# in the case that our rows == chunk
				$self->{output} = undef;
				
			} catch Error with {
				die $sth->errstr;
			};
			
			if (defined($self->{dbh}->errstr)) { die $self->{dbh}->errstr; }
	
			# Check for any errors that might have terminated the loop early
			if ( $sth->err() ) {
				# Premature termination!
				die $sth->errstr;
			}
		} catch Error with {
			$self->{output} = $self->make_error( $data->{id}, shift);
		};

	}

	# Check if we got any errors
	if (!defined($self->{output})) {
		# Make output include the results
		$self->{output} = { rows => $rows, result => $result, id => $data->{id} };
		if (exists($data->{chunked})) {
			$self->{output}->{last_chunk} = 1;
			$self->{output}->{chunked} = $data->{chunked};
		}
	}

	# Finally, we clean up this statement handle
	if (defined($sth)) {
		$sth->finish();
	}
	
	return;
}

# Prints any output to STDOUT
sub output {
	my $self = shift;
	
	# Get the data
	my $data = shift || undef;

	unless (defined($data)) {
		$data = $self->{output};
		$self->{output} = undef;
		# TODO use this at some point
		$self->{error} = undef;
	}
	
	# Freeze it!
	my $outdata = $self->{filter}->put( [ $data ] );

	# Print it!
	print STDOUT @$outdata;
	
	return;
}

1;

__END__

=head1 NAME

POE::Component::EasyDBI::SubProcess - Backend of POE::Component::EasyDBI

=head1 ABSTRACT

This module is responsible for implementing the guts of POE::Component::EasyDBI.
The fork and the connection to the DBI.

=head2 EXPORT

Nothing.

=head1 SEE ALSO

L<POE::Component::EasyDBI>

L<DBI>

L<POE>
L<POE::Wheel::Run>
L<POE::Filter::Reference>

L<POE::Component::DBIAgent>
L<POE::Component::LaDBI>
L<POE::Component::SimpleDBI>

=head1 AUTHOR

David Davis E<lt>xantus@cpan.orgE<gt>

=head1 CREDITS

Apocalypse E<lt>apocal@cpan.orgE<gt>

=head1 COPYRIGHT AND LICENSE

Copyright 2003-2004 by David Davis and Teknikill Software

This library is free software; you can redistribute it and/or modify
it under the same terms as Perl itself.

=cut
