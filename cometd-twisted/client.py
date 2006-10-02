from twisted.python import log
from twisted.web2.client import http
from twisted.web2 import resource, channel, stream, http_headers, responsecode
from twisted.internet import protocol, reactor, defer
from path import path
import urllib
import os
import md5
import time
import simplejson
import string
import base64
import types

# primary functions from the JavaScript client are:
#	- init()
#	- finishInit(type, data, evt, request)  // called when handshake returns
#	- deliver(messagesArr)
#	- disconnect() // tears down connection on current transport
#	- publish(channel, data, properties) // publishes data to server on channel
#	- subscribe(channel, useLocalTopics, objOrFunc, funcName)
#	- subscribed(channel, message)
#	- unsubscribe(channel, useLocalTopics, objOrFunc, funcName)
#	- unsubscribed(channel, message)

class CometdClient:
    
	"""
	    This is a cometd client class. Unlike the JavaScript client, only
	    one connection type is supported.
	"""

	version = 0.1
	minimumVersion = 0.1

	def __init__(self, baseUri="/cometd", server="localhost", port=8080):
		self.server = server
		self.port = port
		self.baseUri = baseUri
		self.clientId = None
		self.initialized = False
		self.connected = False
		self.commandClient = protocol.ClientCreator(reactor, http.HTTPClientProtocol)
		self.channelClient = protocol.ClientCreator(reactor, http.HTTPClientProtocol)

	def _stockHeaders(self):
		thead = http_headers.Headers()
		thead.addRawHeader("Content-Type", "application/x-www-form-urlencoded")
		thead.addRawHeader("Host", self.server)
		return thead
		

	def init(self):
		handshakeObj = {
			"channel": "/meta/handshake",
			"version": self.version,
			"minimumVersion": self.minimumVersion
		}
		d = self.commandClient.connectTCP(self.server, self.port)
		def printResponse(resp):
			def _print(data):
				log.msg(data)
			stream.readStream(resp.stream, _print)

		def _tcpInitFailure(proto):
			log.err("_tcpInitFailure")
			log.err(proto)

		def _tcpInit(proto):
			handshake = stream.MemoryStream(
				urllib.urlencode({ 
					"message": simplejson.dumps([ handshakeObj ])
				})
			)
			req = http.ClientRequest(	"POST", 
										self.baseUri, 
										self._stockHeaders(), 
										handshake)
			proto.submitRequest(req).addCallback(self._finishInit) #.addCallback(printResponse)

		d.addCallback(_tcpInit)
		d.addErrback(_tcpInitFailure)

		reactor.run()

	def _finishInit(self, resp):
		def handleJSON(jsonStr):
			json = simplejson.loads(jsonStr)
			print json
			# print json["authSuccessful"]

		stream.readStream(resp.stream, handleJSON)

if __name__=="__main__":
	client = CometdClient()
	client.init()

# vim:ts=4:noet:
