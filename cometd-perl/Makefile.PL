#use 5.008;
use ExtUtils::MakeMaker;
# See lib/ExtUtils/MakeMaker.pm for details of how to influence
# the contents of the Makefile that is written.

WriteMakefile(
    'NAME'			=> 'Sprocket',
    'VERSION_FROM'	=> 'lib/Sprocket.pm', # finds $VERSION
    'PREREQ_PM'		=> {
#		'Perlbal'	=> undef,
        'Carp'      => undef,
        'JSON'      => '1.05',
        # includes my filter fix
        'POE'       => '0.3502',
        'BSD::Resource' => undef,
        'Class::Accessor::Fast' => undef,
        'HTTP::Request' => undef,
        'HTTP::Status' => undef,
        'HTTP::Response' => undef,
        'HTTP::Date' => undef,
        'Data::UUID' => undef,
        'URI' => undef,
    },
	'dist'         => {
		'SUFFIX'       => ".gz",
		'DIST_DEFAULT' => 'all tardist',
		'COMPRESS'     => "gzip -9vf",
	},

    # TODO other authors here
#    ($] >= 5.005 ?    ## Add these new keywords supported since 5.005
#		(ABSTRACT_FROM	=> 'lib/Sprocket.pm', # retrieve abstract from module
#		 AUTHOR			=> 'David Davis <xantus@cometd.com>') : ()),
);
