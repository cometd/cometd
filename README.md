## The CometD Project ##

Welcome to the [CometD](http://cometd.org) Project, a scalable comet (server push) implementation for the web.


### DIRECTORY LAYOUT ###

    cometd-archetypes - The Maven2 archetypes for quick application development
    cometd-demo       - Demo of the Java server and of the Dojo/jQuery clients
    cometd-java       - The Java server and java client libraries
    cometd-javascript - The JavaScript (Dojo/jQuery) client libraries


### RUNNING THE COMETD DEMO ###

If you have [downloaded](http://download.cometd.org) the release bundle, then the CometD is already built for you.
To run the demonstration with Maven, unzip the release bundle and execute:

    $ cd cometd-demo
    $ mvn jetty:run

then point your browser at http://localhost:8080

Alternately you can deploy the CometD demo war into
any servlet 2.5 or 3.0 server (e.g. jetty, tomcat, glassfish)
with commands like:

    $ cp cometd-demo/target/cometd-demo/target/cometd-demo-*.war  $MY_SERVER/webapps/cometd.war
    cd $MY_SERVER
    <command to run my server>


### REPORTING BUGS ###

Bugs and requests for enhancements should be filed at http://bugs.cometd.org


### BUILDING COMETD ###

If you have cloned the source release, follow the instructions at
http://cometd.org/documentation/building, or just to

    $ mvn install


### LOAD TESTING COMETD ###

To run load testing, open two windows. In the server window run:

    $ cd cometd-java/cometd-java-examples
    $ mvn -Pserver exec:exec

In the client window (potentially on another machine), run

    $ cd cometd-java/cometd-java-examples
    $ mvn -Pclient exec:exec

Then follow instructions on the console to generate load.
