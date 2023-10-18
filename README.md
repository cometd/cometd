## The CometD Project

Welcome to the [CometD](https://cometd.org) Project, a scalable comet (server push) implementation for the web.


### CometD Versions

| CometD Version | Min Java Version | Min Jetty Version | Servlet APIs             |
|----------------|------------------|-------------------|--------------------------|
| `8.0.x`        | Java 17          | Jetty 12.0.x      | EE10 `jakarta.servlet.*` |
| `7.0.x`        | Java 11          | Jetty 11.0.x      | EE9  `jakarta.servlet.*` |
| `6.0.x`        | Java 11          | Jetty 10.0.x      | EE8  `javax.servlet.*`   |
| `5.0.x`        | Java 8           | Jetty 9.4.x       | EE7  `javax.servlet.*`   |


### CometD License

The CometD source code is released under the Apache 2.0 License.


### Project Directory Layout

    cometd-archetypes    - The Maven archetypes for quick application development
    cometd-demo          - Demo of the Java server and of the Dojo/jQuery clients
    cometd-documentation - The CometD documentation
    cometd-java          - The Java server and java client libraries
    cometd-javascript    - The JavaScript (Dojo/jQuery) client libraries


### Running the CometD Demo

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


### Reporting Issues

Issues, bugs and requests for enhancements should be filed at https://bugs.cometd.org


### Building CometD

If you have cloned the source release, follow the instructions at
https://docs.cometd.org/current/reference/#_build, or just do:

    $ mvn install


### CometD Benchmark

The [CometD Benchmark](https://docs.cometd.org/current/reference/#_benchmarking) can be run interactively or in batch mode.

To run the server interactively, open a terminal window and type:

    $ cd cometd-java/cometd-java-benchmark/cometd-java-benchmark-server/
    $ mvn exec:exec

Then follow the instructions printed in the terminal.

To run the client interactively, open another terminal window (potentially on another machine), and type:

    $ cd cometd-java/cometd-java-benchmark/cometd-java-benchmark-client/
    $ mvn exec:exec

Then follow instructions printed on the terminal to generate the load.
