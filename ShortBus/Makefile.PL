#use 5.008;
use ExtUtils::MakeMaker;
# See lib/ExtUtils/MakeMaker.pm for details of how to influence
# the contents of the Makefile that is written.

WriteMakefile(
    'NAME'			=> 'Perlbal::Plugin::ShortBus',
    'VERSION_FROM'	=> 'lib/ShortBus.pm', # finds $VERSION
    'PREREQ_PM'		=> {
		'Perlbal'	=> '1.41',
        'Carp'      => undef,
        'JSON'      => '1.05',
    },
	'dist'         => {
		'SUFFIX'       => ".gz",
		'DIST_DEFAULT' => 'all tardist',
		'COMPRESS'     => "gzip -9vf",
	},

    # TODO other authors here
    ($] >= 5.005 ?    ## Add these new keywords supported since 5.005
		(ABSTRACT_FROM	=> 'lib/ShortBus.pm', # retrieve abstract from module
		 AUTHOR			=> 'David Davis <xantus@cpan.org>') : ()),
);
