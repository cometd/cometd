
This is a demo of the java servlet for cometd


Simplest thing to do is to run 

  mvn jetty:run-war

and then point TWO different instances of browsers at http://localhost:8080

this is to mean, one instance of say, firefox and then another instance of safari, IE, or opera to the same url.  Multiple tabs on the same browser will potentially trigger connection limit code so one instance would appear to be reconnecting over and over, this is due to _browser_ connection limits

cheers


