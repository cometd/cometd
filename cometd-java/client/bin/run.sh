#!/bin/sh
# This run script assumes you have built from source (poor assumption)
cd `dirname $0`/..
REPO=$HOME/.m2/repository

DVERSION="7.0.0.RC6"
echo -n "Jetty Version (from local maven repo) [$DVERSION]: "
read VERSION
[ "$VERSION" = "" ] && VERSION=$DVERSION

CLASSPATH=\
$REPO/org/eclipse/jetty/jetty-util/$VERSION/jetty-util-$VERSION.jar:\
$REPO/org/eclipse/jetty/jetty-io/$VERSION/jetty-io-$VERSION.jar:\
$REPO/org/eclipse/jetty/jetty-http/$VERSION/jetty-http-$VERSION.jar:\
$REPO/org/eclipse/jetty/jetty-client/$VERSION/jetty-client-$VERSION.jar:\
$REPO/javax/servlet/servlet-api/2.5/servlet-api-2.5.jar:\
../api/target/classes:\
../server/target/classes:\
target/classes:\
target/test-classes

java -Xmx2048M -cp $CLASSPATH org.cometd.client.BayeuxLoadGenerator
