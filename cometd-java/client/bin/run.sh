#!/bin/sh
cd `dirname $0`/..
REPO=$HOME/.m2/repository
JETTY_VERSION=7.0.0.M1

java -Xmx2048M -Drepo=$REPO -DSTART=bin/start.config -jar $REPO/org/eclipse/jetty/jetty-start/$JETTY_VERSION/jetty-start-$JETTY_VERSION.jar
