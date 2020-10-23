![GitHub CI](https://github.com/cometd/cometd/workflows/GitHub%20CI/badge.svg)

## The CometD Project ##

Welcome to the [CometD](https://cometd.org) Project, a scalable comet (server push) implementation for the web.


## License ##

The CometD source code is released under the Apache 2.0 License.


### Directory Layout ###

    cometd-archetypes - The Maven archetypes for quick application development
    cometd-demo       - Demo of the Java server and of the Dojo/jQuery clients
    cometd-java       - The Java server and java client libraries
    cometd-javascript - The JavaScript (Dojo/jQuery) client libraries


### Running the CometD Demo ###

If you have [downloaded](https://download.cometd.org) the release bundle, then the CometD is already built for you.
To run the demonstration with Maven, unzip the release bundle and execute:

    $ cd cometd-demo
    $ mvn jetty:run

then point your browser at http://localhost:8080

Alternately you can deploy the CometD demo war file into
any Servlet 2.5 or later server (e.g. jetty, tomcat, glassfish)
with commands like:

    $ cp cometd-demo/target/cometd-demo/target/cometd-demo-*.war  $MY_SERVER/webapps/cometd.war
    cd $MY_SERVER
    <command to run my server>


### Reporting Issues ###

Issues, bugs and requests for enhancements should be filed at https://bugs.cometd.org


### Building CometD ###

If you have cloned the source release, follow the instructions at
https://docs.cometd.org/current/reference/#_build, or just do:

    $ mvn install


### Load Testing CometD ###

To run load testing, open two windows. In the server window run:

    $ cd cometd-java/cometd-java-benchmark/cometd-java-benchmark-server/
    $ mvn exec:exec

In the client window (potentially on another machine), run

    $ cd cometd-java/cometd-java-benchmark/cometd-java-benchmark-client/
    $ mvn exec:exec

Then follow instructions on the console to generate load.
