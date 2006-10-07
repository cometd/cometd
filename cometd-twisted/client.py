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

class HTTPLineNotifierClientProtocol(http.HTTPClientProtocol):

	def __init__(self, lineNotify=None, rawNotify=None, manager=None):
		self.lineNotifier = lineNotify
		self.rawNotifier = rawNotify
		http.HTTPClientProtocol.__init__(self, manager)

	def lineReceived(self, line):
		http.HTTPClientProtocol.lineReceived(self, line)
		if self.lineNotifier is not None:
			self.lineNotifier(line)

	def rawDataReceived(self, data):
		http.HTTPClientProtocol.rawDataReceived(self, data)
		if self.rawNotifier is not None:
			self.rawNotifier(data)

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
	    one connection type (mime-message-block) is supported.
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
		self.authToken = ""
		self.lastTimestamp = ""
		self.lastId = None
		self.handshakeDeferred = defer.Deferred()
		self.commandClient = protocol.ClientCreator(reactor, http.HTTPClientProtocol)
		self.channelClient = protocol.ClientCreator(reactor, HTTPLineNotifierClientProtocol)

		self._isFirstLine = True
		self._backlog = []
		self._lines = []
		self._buffer = ""
		self._mimeBoundary = ""
		self._handlers = {}

	def _stockHeaders(self):
		thead = http_headers.Headers()
		thead.addRawHeader("Content-Type", "application/x-www-form-urlencoded")
		thead.addRawHeader("Host", self.server)
		return thead
		
	def _getMessageRequest(self, message={}, rawheaders={}):
		mstream = stream.MemoryStream(
			urllib.urlencode({ 
				"message": simplejson.dumps([ message ])
			})
		)
		headers = self._stockHeaders() 

		for key in rawheaders.keys():
			headers.addRawHeader(key, rawheaders[key])

		req = http.ClientRequest(	"POST", 
									self.baseUri, 
									headers,
									mstream)
		return req

	def init(self):
		# FIXME: need to add auth data to the handshakeObj!
		handshakeObj = {
			"channel": "/meta/handshake",
			"version": self.version,
			"minimumVersion": self.minimumVersion,
			"supportedConnectionTypes": ["mime-message-block"]
		}
		d = self.commandClient.connectTCP(self.server, self.port)
		"""
		def printResponse(resp):
			def _print(data):
				log.msg(data)
			stream.readStream(resp.stream, _print)

			#.addCallback(printResponse)
		"""

		def _tcpInitFailure(proto):
			log.err("_tcpInitFailure")
			log.err(proto)

		def _tcpInit(proto):
			self.handshakeDeferred.addCallback(self.startup)
			proto.submitRequest(self._getMessageRequest(handshakeObj)).addCallback(self._finishInit) 
		d.addCallback(_tcpInit)
		d.addErrback(_tcpInitFailure)

		reactor.run()

	def _finishInit(self, resp):
		def handleJSON(jsonStr):
			json = simplejson.loads(jsonStr)[0]
			if not json["authSuccessful"]:
				log.err("authentication failure!")
				self.handshakeDeferred.errback(json)
				return

			if json["version"] < self.minimumVersion:
				log.err("cometd protocol version mismatch. We wanted", self.minimumVersion, "but got", str(json["version"]))
				self.handshakeDeferred.errback(json)
				return

			if not "mime-message-block" in json["supportedConnectionTypes"]:
				log.err("desired transport type (mime-message-block) not supported by server")
				self.handshakeDeferred.errback(json)
				return

			self.clientId = json["clientId"]
			self.initialized = True
			self.handshakeDeferred.callback(json)
			print json

		stream.readStream(resp.stream, handleJSON)

	def startup(self, handshakeData):
		if self.connected:
			print "ERROR: already connected!"
			return

		print "startup start"

		self._openTunnelWith({
			"channel": "/meta/connect",
			"clientId": self.clientId,
			"connectionType": "mime-message-block"
		})
		print "startup! end"

	def _openTunnelWith(self, content):
		d = self.channelClient.connectTCP(self.server, self.port)

		def _tcpInit(proto):
			# self.handshakeDeferred.addCallback(self.startup)
			self.connected = True
			proto.lineNotifier = self.handleLine
			proto.rawNotifier = self.handleRaw

			def _readResponse(resp):
				print "_readResponse"
				def _print(data):
					print data
				stream.readStream(resp.stream, _print)

			proto.submitRequest(
				self._getMessageRequest(content)
			) #.addCallback(_readResponse) 

		d.addCallback(_tcpInit)

	def _tunnelOpened(self, data):
		print "_tunnelOpened"
		print str(data)

	def handleLine(self, line):
		# if self._isFirstLine:
		# 	if line[0:2] == "--":
		# 		print "first line!"
		# 		print line
		# 		self._isFirstLine = False
		# else:
		# 	print line
		ctypeStr = "Content-Type: multipart/x-mixed-replace; boundary="
		if line.find(ctypeStr) == 0:
			self._mimeBoundary = "--"+line[len(ctypeStr):].strip()

	def _handleLineStrip(self, line):
		if line[0:2] == "--":
			return line.strip()
		elif line.find("Content-Type: ") == 0:
			return line.strip()
		else:
			return line

	def handleRaw(self, data):
		print "---------------------- raw data --------------------------"
		print data
		print "---------------------- end raw data --------------------------"

		tdata = filter(
			lambda x: len(x.strip()), 
			map(
				self._handleLineStrip, 
				data.split("\n")
			)
		)
		self._lines.extend(tdata)

		# print "---------------- lines ---------------------"
		# print self._lines
		# print "--------------------------------------------"

		if self._mimeBoundary in self._lines:
			boundaryIndex = self._lines.index(self._mimeBoundary)
			while boundaryIndex > 1:
				ctypeIndex = self._lines.index("Content-Type: text/plain")
				jsonStr = string.join(self._lines[(ctypeIndex+1):boundaryIndex], "\n")
				self.deliver(
					simplejson.loads(jsonStr)
				)

				self._lines = self._lines[boundaryIndex:]
				boundaryIndex = self._lines.index(self._mimeBoundary)
				while boundaryIndex == 0:
					self._lines = self._lines[1:]
					if len(self._lines):
						boundaryIndex = self._lines.index(self._mimeBoundary)
					else:
						boundaryIndex = -1

				print "---------------- lines ---------------------"
				print self._lines

		# else:
		# 	log.err("think we got a bad mime block!")
		# 	print "---------------- data ---------------------"
		# 	print data

	def sendMessage(self, message, bypassBacklog=False):
		if bypassBacklog or self.connected:
			# message["connectionId"] = self.connectionId
			message["clientId"] = self.clientId

			d = self.channelClient.connectTCP(self.server, self.port)

			def _msgAck(data):
				print "_msgAck:",
				print data

			def _tcpInit(proto):
				proto.submitRequest(
					self._getMessageRequest(message)
				) #.addCallback(_msgAck) 

			d.addCallback(_tcpInit)

		else:
			self._backlog.append(message)
		

	def deliver(self, messageList):
		for item in messageList:
			self._deliver(item)

		# reactor.iterate()

	def _deliver(self, message):
		print "------------------- delivering message ----------------------"
		print message

		if not message.has_key("channel"):
			print "message has no channel!"
			log.err("message has no channel!")
			log.info(str(essage))
			return

		self.lastMessage = message

		if message.has_key("timestamp"):
			self.lastTimestamp = message["timestamp"]

		if message.has_key("id"):
			self.lastId = message["id"]

		if 	len(message["channel"]) > 5 and \
			str(message["channel"])[0:5] == "/meta":

			if not message["successful"]:
				print ("error for channel: "+message["channel"])
				log.err("error for channel: ", message["channel"])
				return

			if message["channel"] == "/meta/connect":
				log.msg("connected!")
				self.connectionId = message["connectionId"]
				self.connected = True
				self._processBacklog()
			elif message["channel"] == "/meta/reconnect":
				self.connected = True
				self._processBacklog()
			elif message["channel"] == "/meta/subscribe":
				self.subscribed(message["subscription"], message)
			elif message["channel"] == "/meta/unsubscribe":
				self.unsubscribed(message["subscription"], message)

	def _processBacklog(self):
		log.msg("processing the backlog which is ", len(self._backlog), " items long")
		while len(self._backlog):
			self.sendMessage(self._backlog.pop(0), True)

	def subscribed(self, channel, message):
		log.msg("subscribed:", channel)
		pass

	def unsubscribed(self, channel, message):
		log.msg("unsubscribed:", channel)
		pass

	def subscribe(self, channel, callback):
		print "subscribing to "+channel
		
		self.sendMessage({
			"channel": "/meta/subscribe",
			"subscription": str(channel)
		})

		if channel not in self._handlers:
			self._handlers[channel] = [ callback ]
		elif callback not in self._handlers[channel]:
			self._handlers[channel].append(callback)

	def unsubscribe(self, channel, callback):
		self.sendMessage({
			"channel": "/meta/unsubscribe",
			"subscription": str(channel)
		})

		# FIXME: not complete!
		pass

if __name__=="__main__":

	def _move(data):
		print "/magnets/move"
		print data

	client = CometdClient()
	reactor.callWhenRunning(client.subscribe, "/magnets/move", _move)
	client.init()
	# client.subscribe("/magnets/move", _move)

# vim:ts=4:noet:
