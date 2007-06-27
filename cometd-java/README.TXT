
This is a java servlet implementation of cometd server.

This is currently imported from the jetty project as a external svn link.

To compile, you need to use maven 2.x 

   cd $COMETD_HOME/cometd-java/cometd
   mvn install

This produces a jar containing a servlet (with a dependency on
jetty-util jar). This may be used in any webapp.

A demo webapp is built in $COMETD_HOME/cometd-java/cometd/demo
and may be run with the jetty plugin:

   cd $COMETD_HOME/cometd-java/cometd/demo
   mvn jetty:run

then use a browser to surf to port 8080




