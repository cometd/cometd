
This is the cometd project home.

bayeux            - specification
cometd-demo       - Demo of java server and dojo/jquery clients
cometd-java       - java server and java client
cometd-javascript - dojo/jquery clients
cometd-perl       - perl server (ALPHA)
cometd-twisted    - python server (ALPHA)


If you have checked out the source release, you will need
to build cometd first using maven 2.0.9 or later.

    mvn

If you have downloaded the release bundle, then cometd is
already built for you.

To run the demonstration with maven:

    cd cometd-demo
    mvn jetty:run

then point your browser at http://localhost:8080

Alternately you can deploy the cometd demo war into 
any servlet 2.5 or 3.0 server (eg jetty, tomcat, glassfish)
with commands like

    cp cometd-dem0/target/cometd-demo/target/cometd-demo-*.war  $MY_SERVER/webapps/cometd.war
    cd $MY_SERVER
    <command to run my server>

Then point your browser at http://localhost:8080



To build your own cometd application, it is simplest to use maven 
to build a war file with all the required javascript (see cometd-demo
for an example).    If you are not using maven, then see

  cometd-javascript/jquery/target/cometd-javascript-jquery-*.war
  cometd-javascript/dojo/target/cometd-javascript-jquery-*.war

For the required javascript content.  If you do not use
the java server, unpack these files and use them contents
excluding WEB-INF as your docroot.
