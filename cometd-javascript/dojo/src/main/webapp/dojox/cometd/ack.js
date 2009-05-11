dojo.provide("dojox.cometd.ack");
dojo.require("dojox.cometd");

dojo.require("org.cometd.AckExtension");

dojox.cometd.registerExtension('ack', new org.cometd.AckExtension());