# Copyright (C) The Dojo Foundation 2006-2007
# All rights reserved.
#
# Distributed under the terms of the BSD License

import twisted
import twisted.web2
from twisted.web2 import http, resource, channel, stream, server, static, http_headers, responsecode
from twisted.python import log
from twisted.internet import reactor
from twisted.application import service, strports
from path import path
import re
import os
import md5
import time
import simplejson
import string
import base64
import types
import weakref

"""
The cometd modules provides a twisted.web2.resource.Resource endpoint which
acts as a generalized multi-endpoint event router that speaks the cometd JSON
message format and protocol on the wire.
"""

# constants and configuration
verbose = True

# auto-generated configuration
tmp = md5.new()
tmp.update(str(time.ctime()))
mimeBoundary = tmp.hexdigest()
errorHttpCode = 406

# FIXME: implement advices!
# FIXME: need to implement resource constraints

# FIXME: these should probably be pulled from a file or directory containing
# files and read at server startup. Hard-coding them here is fine for
# development but just won't do for deployment.
ConnectionTypes = {

	"iframe": {
		"closeOnDelivery": False,
		"preamble":		"""
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN"
	"http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">

<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en">
	<head>
		<meta http-equiv="Content-Type" content="text/html; charset=utf-8"></meta>
		<title>cometd: The Long Tail of Comet</title>
	</head>
	<body onload="window.parent.dojox.cometd.tunnelCollapse();">
		""",
		"envelope":		"""
			<br />
			<script type="text/javascript">
				window.parent.dojox.cometd.deliver(%s);
			</script>
			<br />
		""" + (" " * 2048), # double this if it's not working
		"keepalive":	"<br>" + (" " * 2048),
		"signoff":		"""
		<script type="text/javascript">
			window.parent.dojox.cometd.disconnect();
		</script>
	</body>
</html>
		""",
		# this tunnelInit is borrowed from Dojo
		"tunnelInit":	"""
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN"
	"http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">

<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en">
	<head>
		<title>cometd: The Long Tail of Comet</title>
		<meta http-equiv="Content-Type" content="text/html; charset=utf-8"></meta>
		<script type="text/javascript">
			// window.parent.console.debug("tunnelInit");
			var noInit = false;
			var domain = "";
			function init(){
				var sparams = document.location.search;
				if(sparams.length >= 0){
					if(sparams.charAt(0) == "?"){
						sparams = sparams.substring(1);
					}
					var ss = (sparams.indexOf("&amp;") >= 0) ? "&amp;" : "&";
					sparams = sparams.split(ss);
					for(var x=0; x<sparams.length; x++){
						var tp = sparams[x].split("=");
						if(typeof window[tp[0]] != "undefined"){
							window[tp[0]] = ((tp[1]=="true")||(tp[1]=="false")) ? eval(tp[1]) : tp[1];
						}
					}
				}

				if(noInit){ return; }
				/*
				if(domain.length > 0){
					document.domain = domain;
				}
				*/
				if(window.parent != window){
					//Notify parent that we are loaded.
					window.parent.dojox.cometd.tunnelInit(window.location, document.domain);
				}
			}
		</script>
	</head>
	<body onload="try{ init(); }catch(e){ alert(e); }">
		<h4>cometd: The Long Tail of Comet</h4>
	</body>
</html>""",
		"contentType": "text/html"
	},

	"callback-polling": {
		# NOTE: the "callback-polling" method can be used via ScriptSrcIO for
		# x-domain polling
		"closeOnDelivery": True,
		"preamble":		"",
		# "envelope":		"cometd.deliver(%s);",
		"envelope":		"(%s)",
		"keepalive":	" ",
		"signoff":		"",
		"tunnelInit":	"",
		"contentType": "text/javascript"
	},

	"long-polling": {
		"closeOnDelivery": True,
		"preamble":		"",
		"envelope":		"%s",
		"keepalive":	"",
		"signoff":		"",
		"tunnelInit":	"",
		"contentType": "text/plain"
	},

	"ie-message-block": {
		"closeOnDelivery": False,
		"preamble":		"""<?xml version="1.0" encoding="UTF-8"?>
			<cometd>
		""",
		"envelope":		"<message><![CDATA[%s]]></message>",
		"keepalive":	"<message><![CDATA[]]></message>",
		"signoff":		"""
			</cometd>
		""",
		"tunnelInit":	"",
		"contentType": "text/xml"
	},

	"mime-message-block": {
		"closeOnDelivery": False,
		"preamble":		"--"+mimeBoundary+"\r\n",
		"envelope":		"""Content-Type: text/plain\r\n\r\n
%s
\r\n
--"""+mimeBoundary+"\r\n",
		"signoff":		"\n--"+mimeBoundary+"--\n",
		"tunnelInit":	"",
		"contentType": "multipart/x-mixed-replace; boundary=%s" % (mimeBoundary,)
	},
	# failed experiment
	#		"preamble":		"",
	#		"envelope":		"\n--"+mimeBoundary+"""
	#Content-Type: text/plain\n\n
	#%s
	#
	#""",
	# --"""+mimeBoundary+"\n",

#	"text-stream": {
#		"closeOnDelivery": False,
#		"preamble":		"--"+mimeBoundary+"\r\n",
#		"envelope":		"""\r\n%s\r\n--"""+mimeBoundary+"\r\n"+(1024*" "),
#		"signoff":		"\r\n--"+mimeBoundary+"--\r\n",
#		"tunnelInit":	"",
#		"contentType": "text/plain"
#	},

#	"flash": {
#		"closeOnDelivery": False,
#		"preamble":		"",
#		"envelope":		"",
#		"signoff":		"",
#		"tunnelInit":	"",
#		"contentType": "text/xml"
#	}
}

# need to specify http-polling for entirely disconnected clients!

SupportedConnectionTypes = [
	"callback-polling", "long-polling", 
	 "mime-message-block", "iframe", 
	 # "ie-message-block", # doesn't really work?
]

def getIdStr(length=32):
	if os.__dict__.has_key("urandom"):	# python 2.4
		return base64.encodestring(os.urandom(length))[:-1]
	else:								 # python 2.3 on unix-like systems
		return base64.encodestring(open("/dev/urandom").read(length))[:-1]

def getTimestamp():
	# YYYY-MM-DDThh:mm:ss.ss
	# FIXME: need to add accurate sub-second time-stamp data!
	# NOTE: see http://feedparser.org/docs/date-parsing.html for background
	# FIXME: should we add a "Z" at the end for RFC 3339 compat?
	return time.strftime("%Y-%m-%dT%H:%M:%S.00", time.gmtime())
	
class Connection:
	"""
	The cometd Connecton class is responsible for a logical connection between
	a client and a server. This is *NOT* implemented as a
	twisted.internet.protocol.Protocol subclass due to the seeming magicness of
	Protocol instances and the Factories that create/use them.  Instead, the
	Connection class manages one (or more) instances of stream.ProducerStream
	instances which constitute output buffers for the connection.

	The Connection class knows how to set up and tear down streams, register
	and unregister with a connection registry, and communicate with the event
	router.
	"""

	# an incrementing ID basis for connections
	counter = 0

	def __init__(self, request, message, client):
		"""
		Initializing a cometd Connection object creates a "stream" object
		that all delivered events are passed down to. Encapsulating this allows
		our Connection to handle multiple requests over its lifetime.
		"""
		# should be set or re-set depending on what type of request is coming in
		self.client = client
		self.endpointId = 0
		# we fall back to polling if otherwise unspecified
		self.connectionType = message["connectionType"]
		if verbose:
			log.msg("****************************************************")
			log.msg("connectionType:", self.connectionType);
			log.msg("****************************************************")
		self.ctypeProps = ConnectionTypes[self.connectionType]
		self.contentType = self.ctypeProps["contentType"]
		self.backlog = []
		self.stream = stream.ProducerStream()
		self.id = getIdStr()

		self.jsonp = False
		self.jsonpCallback = None

		self.initFromRequest(request, message)

	def initFromRequest(self, request, message):
		# if the /connect endpoint is our originator, we need to return with
		# the correct header and initialization message
		resp = {
			"channel":		"/meta/connect",
			"successful": 	True,
			"error": 		self.client.lastError,
			"authToken": 	self.client.authToken,
			"clientId":		self.client.id,
			"advice":		{
				"reconnect": "retry"
			}
		}

		if verbose: log.msg(self.ctypeProps["preamble"])
		self.stream.write(self.ctypeProps["preamble"])

		if "jsonp" in request.args: # FIXME: hack!
			self.jsonp = True
			self.jsonpCallback = request.args["jsonp"][0]

		self.deliver(resp)

	def deliver(self, message=None):
		# should this be using twisted.internet.reactor.callLater(seconds,
		# callback) to actually preform the writes?
		delivered = False
		if message is not None:
			self.backlog.append(message)

		if not self.stream.closed:
			if len(self.backlog):
				delivered = True
				# log.msg(
				# 	self.ctypeProps["envelope"] % (
				# 		simplejson.dumps(self.backlog[0]),
				# 	)
				# )
				if self.jsonp: # FIXME: hack!
					self.stream.write(self.jsonpCallback+"(")

				self.stream.write(
					self.ctypeProps["envelope"] % (
						simplejson.dumps(self.backlog),
					)
				)

				self.backlog = []

				if self.jsonp:
					self.stream.write(");")

			if self.ctypeProps["closeOnDelivery"] and delivered is not False:
				self.stream.finish()

	def reopen(self, request, message):
		if self.stream.closed:
			if "jsonp" in request.args: # FIXME: hack!
				self.jsonp = True
				self.jsonpCallback = request.args["jsonp"][0]
			self.stream = stream.ProducerStream()
			self.deliver()

class Client:
	def __init__(self, id=None, authSuccessful=False, authToken=None, lastError=""):
		self.connection = None
		self.id = id
		if not self.id:
			self.id = getIdStr()
		self.authSuccessful = authSuccessful
		self.authToken = authToken
		self.lastError = lastError

	def setConnection(self, conn):
		self.connection = conn

	def __del__(self):
		del self.connection

def buildResponse(data, code=200, type="text/html", headers={}):
	respStream = None
	if isinstance(data, str):
		respStream = stream.MemoryStream(data)
	else:
		respStream = data
	parts = type.split("/", 1)
	thead = http_headers.Headers()
	thead.addRawHeader("Content-Type", type)
	for name in headers:
		thead.addRawHeader(name, headers[name])
	return http.Response(code, stream=respStream, headers=thead)

class cometd(resource.PostableResource):

	version = 1.0
	minimumVersion = 1.0


	def __init__(self, credChecker=None):
		self.credChecker = credChecker
		self.clients = {}
		self.subscriptions = { }
		#	"__cometd_subscribers": {}

	# FIXME: we need to implement client culling. Choices are between keeping a
	# list of update times and associated clients, but that requires a
	# per-message, per-client update to the state to remove the clients from
	# the "below the line" state.

	############################################################################
	# UTILITY METHODS
	############################################################################

	############################################################################
	# resource.Resource METHODS
	############################################################################

	def locateChild(self, request, segments):
		# when we're reached, switch immediately to render mode
		if verbose:
			log.msg(request)
			log.msg(segments)
		return (self, server.StopTraversal)

	def render(self, request):
		"""
		parse the request, dispatching it to the event router as necessaray and
		returning errors where appropriate
		"""
		if verbose: log.msg("----------------------- render -----------------------------")
		messages = None
		resp = []

		# we'll get called as the result of a post or get
		if verbose: log.msg(request.args)

		# if we get a tunnelInit request in the form of:
		# 	http://blah.endpoint.com/cometd/?tunnelInit=iframe&domain=endpoint.com
		# just pass back a MemoryStream that has the right junk
		if 	request.args.has_key("tunnelInit") and \
			ConnectionTypes.has_key(request.args["tunnelInit"][0]):

			resp = ConnectionTypes[request.args["tunnelInit"][0]]["tunnelInit"]
			# log.msg(resp)
			# FIXME: should we be getting the content type from the
			# ConnectionTypes object?
			return buildResponse(resp)

		# otherwise if we got a "message" parameter, deserialize it
		if request.args.has_key("message"):
			try:
				# log.msg(request.args["message"][0])
				messages = simplejson.loads(request.args["message"][0])
			except ValueError:
				if verbose: log.msg("message parsing error")
				return buildResponse("message not valid JSON", errorHttpCode, "text/plain")
		else:
			return buildResponse("no message provided. Please pass a message parameter to cometd", 400)

		ctr = 0
		if verbose: log.msg("messages:", type(messages), ":",  simplejson.dumps(messages))
		while len(messages):
			if verbose: log.msg(len(messages))
			m = messages.pop(0)
			if verbose:
				log.msg("tmp message:", type(m), ":",  simplejson.dumps(m))
			if not isinstance(m, dict):
				continue
			"""
			if isinstance(m, types.StringTypes):
				log.msg(m)
				break
			if isinstance(m, types.NoneType):
				continue
			"""
			if not m.has_key("channel"):
				resp.append({"error":"invalid message passed"})
				break
				# continue

			chan = m["channel"]
			if chan == "/meta/handshake" and ctr == 0:
				# looks like we'll need to create a Connection
				return self.initHandshake(request, m)
			elif chan == "/meta/connect" and ctr == 0:
				# finish connection initialization!
				return self.connect(request, m)
			elif chan == "/meta/reconnect" and ctr == 0: # FIXME: legacy!
				return self.connect(request, m)
			elif chan == "/meta/subscribe":
				resp.append(self.subscribe(request, m))
			elif chan == "/meta/unsubscribe":
				resp.append(self.unsubscribe(request, m))
			else:
				# otherwise we're publishing. Route the message to listeners
				resp.append(self.route(request, m))
			# FIXME: implement /meta/ping and /meta/status !!
			ctr += 1

		# FIXME: 
		#		need to determine here if/how we should be delivering back on
		#		an open connection channel if one was pre-existing
		return buildResponse(simplejson.dumps(resp), type="text/plain")

	############################################################################
	# PROTOCOL METHODS
	############################################################################

	def initHandshake(self, request, message):
		# handle initial auth and create a Connection that subsequent
		# /meta/connect messages can talk to

		# tell the client what we can and can't support:

		# FIXME: is there a way to keep from re-defining/copying this data
		# structure?
		resp = {
			"channel":	"/meta/handshake",
			"version":	self.version,
			"minimumVersion": self.minimumVersion,
			"supportedConnectionTypes":	SupportedConnectionTypes
		}

		client = self.checkHandshakeAuth(request, message)
		resp["clientId"] = client.id
		resp["successful"] = client.authSuccessful
		resp["authToken"] = client.authToken
		resp["error"] = client.lastError
		if message["id"] is not None:
			resp["id"] = message["id"]

		rstr = simplejson.dumps([ resp ])
		if verbose: log.msg("initHandshake response:", rstr)

		# accomidation for JSONP handshakes
		if "jsonp" in request.args:
			rstr = request.args["jsonp"][0]+"("+rstr+");"

		return buildResponse(rstr, type="text/plain")

	# FIXME: should we look into using twisted.cred here to handle auth types?
	def checkHandshakeAuth(self, request, message):
		(success, token, error) = self.checkCredentials(request, message)

		# FIXME: mst suggests using something like md5(clientIP, localMAC, time) instead
		if success:
			client = Client(authSuccessful=success, authToken=token, lastError=error)
			self.clients[client.id] = client

		return client

	def checkCredentials(self, request, message):
		# return's a tuple with the form:
		#	(success, token, error)
		#
		# FIXME: plug in auth check here!
		if self.credChecker is not None:
			return self.credChecker.checkCredentials(request, message)
		return (True, None, None)

	def _checkClient(self, request, message):
		if "clientId" not in message or \
			message["clientId"] not in self.clients:
			return False
		else:
			return True

	def _sanityCheckConnection(self, request, message):
		isSane = True
		errorResp = None
		error = ""

		# sanity check the connection request
		if "connectionType" not in message or \
			message["connectionType"] not in SupportedConnectionTypes:
			isSane = False
			error = "invalid connectionType requested"
		elif not self._checkClient(request, message):
			isSane = False
			error = "invalid clientId provided"
			# log.msg(message["clientId"])

		if not isSane:
			resp = simplejson.dumps({ "error": str(error) })
			errorResp = buildResponse(resp, 500, "text/plain")

		return (isSane, errorResp)

	def connect(self, request, message):
		"""
		Create a new connection object for the client if one does not already
		exist. Otherwise, handles wait state for existing connection.
		"""

		(isSane, errorResp) = self._sanityCheckConnection(request, message)
		if not isSane: return errorResp

		clientId = message["clientId"]
		client = self.clients[clientId]

		(	client.authSuccessful,
			client.authToken,
			client.lastError	) = self.checkCredentials(request, message)

		if not client.authSuccessful:
			# auth failure, nuke the client from the list
			del self.clients[clientId]
			resp = simplejson.dumps({ "error": client.error })
			if verbose:
				log.msg(resp)
			return buildResponse(resp, 500, "text/plain")

		if client.connection is not None:
			# if verbose:
			# 	log.msg("from a reconnect!")
			client.connection.reopen(request, message)
		else:
			# if the request is sane and valid, set up a new Connection object
			# which will initiate the response and manage it from here on out
			conn = Connection(request, message, client)
			client.setConnection(conn)

		return buildResponse(client.connection.stream, type=client.connection.contentType)

	def subscribe(self, request, message):
		# get the client and the channel here
		# self._subscribe()
		if not self._checkClient(request, message):
			# FIXME: we should probably send advice here instead of just raw failure
			resp = { "error": "invalid clientId provided" }
			if verbose:
				log.msg(simplejson.dumps(resp))
			return resp

		client = self.clients[message["clientId"]]
		if verbose: log.msg(client)

		# FIXME: need to switch to using globbing subscribe!
		# self._subscribe(client, message["subscription"])
		self._globbing_subscribe(client, message["subscription"])

		# FIXME: hoist template object to top level to avoid redef
		resp = {
			"channel":		"/meta/subscribe",
			"subscription":	message["channel"],
			"successful":	True
			# "authToken":	"SOME_NONCE"
		}
		if message["id"] is not None:
			resp["id"] = message["id"]

		# FIXME: should we be calling client.deliver and having *that* dispatch
		# down to the correct connection object?
		client.connection.deliver(resp)
		# return { "successful": True }
		return resp

	def unsubscribe(self, request, message):
		if not self._checkClient(request, message):
			resp = { "error": "invalid clientId provided" }
			return resp

		client = self.clients[message["clientId"]]
		if verbose: log.msg(client)

		self._globbing_unsubscribe(client, message["subscription"])

		# FIXME: hoist template object to top level to avoid redef
		resp = {
			"channel":		"/meta/unsubscribe",
			"subscription":	message["channel"],
			"successful":	True
		}
		if message["id"] is not None:
			resp["id"] = message["id"]

		# log.msg(simplejson.dumps(resp))
		client.connection.deliver(resp)
		# return { "successful": True }
		return resp
		
	def route(self, request, message):
		return self._globbing_route(request, message)

	def _globbing_unsubscribe(self, client, chan):
		"remove a subscription"

		cparts = chan.split("/")[1:]
		if verbose: log.msg(cparts)
		root = self.subscriptions
		for part in cparts: # create parts of the topic tree that don't yet exist
			if not part in root:
				return
			root = root[part] 

		del root["__cometd_subscribers"][client.id]

	def _globbing_subscribe(self, client, chan):
		"set up a subscription"

		# the channels data structure is a tree, relying on the speed of Python
		# dictionary lookups to quickly return a list of interested clients.
		# NOTE: while we do "glob", it's only on terminal path components

		cparts = chan.split("/")[1:]
		if verbose: log.msg(cparts)
		root = self.subscriptions
		for part in cparts: # create parts of the topic tree that don't yet exist
			if not part in root:
				if verbose:
					log.msg("creating part: ", part)
				root[part] = { "__cometd_subscribers": None }
				# root[part] = weakref.WeakValueDictionary()
				root[part]["__cometd_subscribers"] = weakref.WeakValueDictionary()

			root = root[part] 

		root["__cometd_subscribers"][client.id] = client

	def _globbing_route(self, request, message):
		"""
		Event routing and delivery. The guts of cometd.
		"""
		success = { 
			"successful": True,
			"channel": message["channel"]
		}
		if message["id"] is not None:
			success["id"] = message["id"]

		cparts = message["channel"].split("/")[1:]
		if verbose: log.msg(cparts)
		root = self.subscriptions
		# FIXME: need to implement the "**" glob
		for part in cparts: # FIXME: is iteration order garunteed?
			if "*" in root:
				# log.msg("delivering to wildcard subscribers")
				subs = root["*"]["__cometd_subscribers"]
				for client in subs:
					if verbose: log.msg(client)
					# FIXME: check for "openness"?
					subs[client].connection.deliver(message)
			if not part in root:
				if verbose: log.msg("no part:", part, "matches for delivery")
				return success
			root = root[part]
			subs = root["__cometd_subscribers"]
			for client in subs:
				subs[client].connection.deliver(message)
		return success
		# FIXME: aggregate ACKs!

	###########################################################################
	# old code, unused now
	###########################################################################
	def _subscribe(self, client, chan):
		"set up a subscription"
		# the channels data structure is a flat map, with each value being map of subsribed clients
		# dictionary lookups to quickly return a list of interested clients.
		# NOTE: we are not currently supporting the "*" glob operator in channels
		cparts = chan.split("/")[1:]
		if verbose: log.msg(cparts)
		root = self.subscriptions
		if not chan in root:
			if verbose:
				log.msg("creating channel: ", chan)
			root[chan] = weakref.WeakValueDictionary()

		root[chan][client.id] = client

	def _flat_route(self, request, message):
		"""
		Event routing and delivery. The guts of cometd.
		"""
		if verbose: log.msg(message["channel"])
		root = self.subscriptions
		if root.has_key(message["channel"]):
			subs = root[message["channel"]]
			for client in subs:
				subs[client].connection.deliver(message)
		return { "successful": True }

# vim:ts=4:noet:
