This directory contains a build of dojo using the cometd profile at
svn+ssh://svn.dojotoolkit.org/var/src/dojo/util/trunk/buildscripts/profiles/cometd.profile.js

The profile is built from a dojo release with the commands below that need DOJO_HOME
and COMETD_HOME to be set:


cd $DOJO_HOME/util/buildscripts
./build.sh profile=cometd action=release
cd $DOJO_HOME
cp release/dojo/dojo/dojo.js release/dojo/dojo/dojo.js.uncompressed.js $COMETD_HOME/examples/dojo/dojo
cp release/dojo/dojox/cometd.js release/dojo/dojox/cometd.js.uncompressed.js $COMETD_HOME/examples/dojo/dojox
cp release/dojo/dojox/cometd/[a-zA-Z]*.js $COMETD_HOME/examples/dojo/dojox/cometd/
cd $COMETD_HOME/examples/dojo


Some demos may need additional parts of dojo, in which case we should create a cometd-examples
profile.


