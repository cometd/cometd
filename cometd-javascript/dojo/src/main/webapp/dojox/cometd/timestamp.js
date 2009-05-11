dojo.provide("dojox.cometd.timestamp");
dojo.require("dojox.cometd");
dojo.require("org.cometd.TimeStampExtension");

dojox.cometd.registerExtension('timestamp', new org.cometd.TimeStampExtension());