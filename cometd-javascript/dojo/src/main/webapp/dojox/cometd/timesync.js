dojo.provide("dojox.cometd.timesync");
dojo.require("dojox.cometd");
dojo.require("org.cometd.TimeSyncExtension");

dojox.cometd.registerExtension('timesync', new org.cometd.TimeSyncExtension());