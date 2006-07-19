# Copyright (C) Alex Russell 2006
# All rights reserved.
#
# Distributed under the terms of the BSD License

from twisted.web2 import http, resource, channel, stream, server, static, http_headers, responsecode
from twisted.python import log
from twisted.internet import reactor
from twisted.application import service, strports
from path import path
import os
import md5
import time
import simplejson
import string
import base64

"""
The cometd modules provides a twisted.web2.resource.Resource endpoint which
acts as a generalized multi-endpoint event router that speaks the cometd JSON
message format and protocol on the wire.

Dependencies:

	* Python >= 2.3 
	* Twisted Python 2.4.0
	* Twisted Web2 0.2
	* simplejson >= 1.3

Initial connection setup specifies to the system a message envelope type for
all communications on a particular channel, but inside this envelope a
normalized JSON structure is used to communicate with all endpoints.

The event router is channel based, with rules based on longest-prefix match
dispatching for registered listeners. The "/meta/*" channel is reserved for
communications with the event router itself (including connection setup,
tear-down, and re-connection), and all conformant clients and servers must
implement the following meta-channel  verbs:
	
	* handshake
	* connect
	* reconnect
	* disconnect
	* status
	* subscribe
	* unsubscribe
	* ping

All cometd /meta/ channel messages contain a protocol version number and all
messages generated from (or routed through) an event router contain a message
ID which is unique to the router. No randomness is required in these
identifiers although routers are expected to drop messages which they have
previously "seen".

All cometd messages are encoded in JSON format, but information in discrete
messages MAY be repeated in HTTP headers in order to speed message routing.
This is an example of the messages exchanged durring a connection setup
process:

	// from client, to server
	{
		"channel":			"/meta/handshake",
		// all meta channel messages MUST contain the protocol version the
		// client expects
		"version":			0.1,
		// the oldest version of the protocol that this client will support
		"minimumVersion":	0.1,
		// the authScheme is outside the realm of this specification and
		// provided here for illustration only. It's also optional.
		"authScheme":		"SHA1",
		// the authUser and authToken are optional and authScheme dependent
		"authUser":			"alex",
		"authToken":		"HASHJIBBERISH"
	}

	// NOTE: data should be POSTed with an encoding of
	// application/x-www-form-urlencoded, and the preceeding payload is
	// expected to be stored in the "message" parameter

	// from server, to client
	{
		"channel":					"/meta/handshake",
		// preferred protocol version
		"version":					0.1,
		// the oldest version of the protocol that this server will support
		"minimumVersion":			0.1,
		"supportedConnectionTypes":	["iframe", "flash", "http-polling"],
		"clientId":					"SOME_UNIQUE_CLIENT_ID"
		"authSuccessful":			true,
		// authToken is auth scheme dependent and entirely optional
		"authToken":				"SOME_NONCE_THAT_NEEDS_TO_BE_PROVIDED_SUBSEQUENTLY",
	}

	// finally, the client opens up a tunnel by creating a new iframe whose
	// initial contents were retreived from the following URL:
	//		http://blah.endpoint.com/cometd/?tunnelInit=iframe&domain=endpoint.com
	// this is an unfortunate workaround for some cross-domain intialization
	// issues with iframes. Ideally the "tunnelInit" block will never need to
	// be sent for most transports.
	//
	// here's what the client then POST's:
	{
		"channel":	"/meta/connect",
		"clientId":	"SOME_UNIQUE_CLIENT_ID",
		"connectionType": "iframe",
		// optional
		"authToken":"SOME_NONCE_PREVIOUSLY_PROVIDED_BY_SERVER"
	}

	// NOTE: data should be POSTed with an encoding of
	// application/x-www-form-urlencoded, and the preceeding payload is
	// expected to be stored in the "message" parameter

	// the server now replies with the preamble followed by any number of
	// messages encoded in the tunnel-specific envelope:
	<!-- begin preamble -->
	<html>
		<head>
			<title>Comet -- cleaning up web development</title>
			<script type="text/javascript">
				window.parent.cometd.deliver({
					// user-sent data
					"channel":		"/meta/connect",
					"successful":	true,
					"error":		"",
					"connectionId":	"/meta/connections/26",
					"timestamp":	"TimeAtServer",
					// optional
					"authToken":	"SOME_NONCE_THAT_NEEDS_TO_BE_PROVIDED_SUBSEQUENTLY"
				});
			</script>
		</head>
		<body>
	<!-- end preamble -->
	<!-- begin envelope -->
			<script type="text/javascript">
				window.parent.cometd.deliver({
					// user-sent data
					"data": {
						"someField":	["some", "random", "values"],
					},
					// the usual message meta-data
					"channel":		"/originating/channel",
					"id":			"slkjdlkj30",
					"timestamp":	"TimeAtServer",
					// optional meta-data
					"authToken":	"SOME_NONCE_THAT_NEEDS_TO_BE_PROVIDED_SUBSEQUENTLY"
				});
			</script>
			<br><!-- insert 2K of whitespace here -->
	<!-- end envelope -->
	...
	<!-- begin signoff -->
			<script type="text/javascript">
				window.parent.cometd.tunnelCollapse();
			</script>
		</body>
	</html>
	<!-- end signoff -->
	
Upon connection, clients are implicitly subscribed to a connection-specific
channel located at:

	/meta/clients/[SOME_UNIQUE_CLIENT_ID]

The client ID turns out to be very important to subsequent connection.

	// reconnection is very similar to initial connection:
	{
		"channel":		"/meta/reconnect",
		"clientId":		"SOME_UNIQUE_CLIENT_ID",
		"timestamp":	"LastReceivedTimeAtServer",
		"id":			"LastReceivedMessageId"
		"connectionId":	"/meta/connections/26",
		"connectionType": "iframe", // FIXME: is this necessaray?
		// optional
		"authToken":	"SOME_NONCE_PREVIOUSLY_PROVIDED_BY_SERVER"
	}

	// and the response looks almost identical to normal
	// preamble/envelope/signoff event delivery except that queued messages may
	// be delivered en-masse. We don't cover it here for the sake of brevity.

	// Subscribing and unsubscribing to channel is straightforward. Subscribing:
	{
		"channel":		"/meta/subscribe",
		"subscription":	"/some/other/channel",
		// optional
		"authToken":	"SOME_NONCE_PREVIOUSLY_PROVIDED_BY_SERVER"
	}

	// response to subscription
	{
		"channel":		"/meta/subscribe",
		"subscription":	"/some/other/channel",
		"successful":	true,
		"error":		"",
		// optional
		"authToken":	"SOME_NONCE"
	}

	// unsubscription for same:
	{
		"channel":		"/meta/unsubscribe",
		"subscription":	"/some/other/channel",
		// optional
		"authToken":	"SOME_NONCE_PREVIOUSLY_PROVIDED_BY_SERVER"
	}

	// and the server response:
	{
		"channel":		"/meta/unsubscribe",
		"subscription":	"/some/other/channel",
		"successful":	true,
		"error":		"",
		// optional
		"authToken":	"SOME_NONCE"
	}

	// FIXME: need to specify the "status" and "ping" message types!!

	// NOTE: the "status" message type may be used for introspection and channel
	// subscription queries

	// NOTE: the "/meta/ping" channel may be safely ignored by servers and
	// clients alike. It may be used as a keepalive mechanism.

	// FIXME: need to build in a provision for batching of queued messages in
	// the returned data structure

NOTE: the 0.1 version of the protocol does NOT acknowledge passing messages
beyond a single router connected to multiple clients. Future protocol versions
may include provisions for this.

NOTE: servers SHOULD maintain event delivery queues for intermittently
disconnected clients. The protocol does not currently specify a way for clients
to specify their per-conection "age limit" nor does it specify a policy for
server event expiration.

TODOC: when subscription happens, it's *clients* that are subscribed, not the
acting tunnels or connections.
"""

tmp = md5.new()
tmp.update(str(time.ctime()))
mimeBoundary = tmp.hexdigest()

# FIXME: these should probably be pulled from a file or directory containing
# files and read at server startup. Hard-coding them here is fine for
# development but just won't do for deployment.
ConnectionTypes = {
	"iframe": {
		"deliverMulti": True,
		"closeOnDelivery": False,
		"preamble":		"""
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN"
	"http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">

<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en">
	<head>
		<meta http-equiv="Content-Type" content="text/html; charset=utf-8"></meta>
		<title>cometd: The Long Tail of Comet</title>
	</head>
	<body onload="window.parent.cometd.tunnelCollapse();">
		""",
		"envelope":		"""
			<br />
			<script type="text/javascript">
				window.parent.cometd.deliver(%s);
			</script>
			<br />
		""" + (" " * 2048), # double this if it's not working
		"keepalive":	"<br>" + (" " * 2048),
		"signoff":		"""
		<script type="text/javascript">
			window.parent.cometd.disconnect(%s);
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
			// window.parent.dojo.debug("tunnelInit");
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
					window.parent.cometd.tunnelInit(window.location, document.domain);
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
		"deliverMulti": True,
		"closeOnDelivery": True,
		"preamble":		"",
		"envelope":		"cometd.deliver(%s);",
		"keepalive":	" ",
		"signoff":		"",
		"tunnelInit":	"",
		"contentType": "text/javascript"
	},
	"long-polling": {
		"deliverMulti": False,
		"closeOnDelivery": True,
		"preamble":		"",
		"envelope":		"%s",
		"keepalive":	"",
		"signoff":		"",
		"tunnelInit":	"",
		"contentType": "text/plain"
	},
	"http-polling": {
		# NOTE: polling doesn't have a "wrapper", as we pass back an array of
		# JSON objects for direct delivery
		"deliverMulti": False,
		"closeOnDelivery": True,
		"preamble":		"",
		"envelope":		"%s",
		"keepalive":	"",
		"signoff":		"",
		"tunnelInit":	"",
		"contentType": "text/plain"
	},
	"ie-message-block": {
		"deliverMulti": True,
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
		"deliverMulti": True,
		"closeOnDelivery": False,
		"preamble":		"--"+mimeBoundary+"\r\n",
		"envelope":		"""Content-Type: text/plain\r\n\r\n
%s
\r\n
--"""+mimeBoundary+"\r\n",

#		"preamble":		"",
#		"envelope":		"\n--"+mimeBoundary+"""
#Content-Type: text/plain\n\n
#%s
#
#""",
# --"""+mimeBoundary+"\n",
		"signoff":		"\n--"+mimeBoundary+"--\n",
		"tunnelInit":	"",
		"contentType": "multipart/x-mixed-replace; boundary=%s" % (mimeBoundary,)
	},
	"flash": {
		"deliverMulti": True,
		"closeOnDelivery": False,
		"preamble":		"",
		"envelope":		"",
		"signoff":		"",
		"tunnelInit":	"",
		"contentType": "text/xml"
	}
}

SupportedConnectionTypes = [
	"iframe", "ie-message-block", "mime-message-block", 
	"callback-polling", "long-polling", "http-polling"
]

def getIdStr(length=32):
	if os.__dict__.has_key("urandom"):	# python 2.4
		return base64.encodestring(os.urandom(length))[:-1]
	else:								 # python 2.3 on unix-like systems
		return base64.encodestring(open("/dev/urandom").read(length))[:-1]

class Connection:
	"""
	The cometd Connecton class is responsible for a logical connection
	between a client and a server. This is *NOT* implemented as a
	twisted.internet.protocol.Protocol subclass due to the seeming
	magicness of Protocol instances and the Factories that create/use them.
	Instead, the Connection class manages one (or more) instances of
	stream.ProducerStream instances which constitute output buffers for the
	connection.

	The Connection class knows how to set up and tear down streams,
	register and unregister with a connection registry, and communicate
	with the event router.
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
		log.msg("****************************************************")
		log.msg("connectionType:", self.connectionType);
		log.msg("****************************************************")
		self.ctypeProps = ConnectionTypes[self.connectionType]
		self.contentType = self.ctypeProps["contentType"]
		self.backlog = []
		self.stream = stream.ProducerStream()
		self.id = getIdStr()

		self.initFromRequest(request, message)

	def initFromRequest(self, request, message):
		# if the /connect endpoint is our originator, we need to return with
		# the correct header and initialization message
		resp = {
			"channel":		"/meta/connect",
			"successful": 	True,
			"error": 		self.client.lastError,
			"authToken": 	self.client.authToken,
			"connectionId":	self.id,
			"clientId":		self.client.id,
			"timestamp": 	str(time.ctime())
		}

		# log.msg(self.ctypeProps["preamble"])
		self.stream.write(self.ctypeProps["preamble"])

		self.deliver(resp)

	def deliver(self, message=None):
		# should this be using twisted.internet.reactor.callLater() to actually
		# preform the writes?
		delivered = False
		if message is not None:
			self.backlog.append(message)

		if not self.stream.closed:
			while len(self.backlog):
				delivered = True
				# log.msg(
				# 	self.ctypeProps["envelope"] % (
				# 		simplejson.dumps(self.backlog[0]),
				# 	)
				# )
				self.stream.write(
					self.ctypeProps["envelope"] % (
						simplejson.dumps(self.backlog.pop(0)),
					)
				)
				if not self.ctypeProps["deliverMulti"]: break

			if self.ctypeProps["closeOnDelivery"] and delivered is not False:
				self.stream.finish()

	def reopen(self):
		if self.stream.closed:
			self.stream = stream.ProducerStream()
			self.deliver()

class Client:
	def __init__(self, id=None, authSuccess=False, authToken=None, lastError=""):
		self.connection = None
		self.id = id
		if not self.id:
			self.id = getIdStr()
		self.authSuccess = authSuccess
		self.authToken = authToken
		self.lastError = lastError

	def setConnection(self, conn):
		self.connection = conn

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

	version = 0.1
	minimumVersion = 0.1

	clients = {}
	connections = {} # indexed by client ID
	subscriptions = {
		"__cometd_subscribers": {} 
	}

	############################################################################
	# UTILITY METHODS
	############################################################################

	############################################################################
	# resource.Resource METHODS
	############################################################################

	def locateChild(self, request, segments):
		# when we're reached, switch immediately to render mode
		log.msg(request)
		log.msg(segments)
		return (self, server.StopTraversal)

	def render(self, request):
		"""
		parse the request, dispatching it to the event router as necessaray and
		returning errors where appropriate
		"""
		message = None

		# we'll get called as the result of a post or get
		log.msg(request.args)

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
				message = simplejson.loads(request.args["message"][0])
			except ValueError:
				log.msg("message parsing error")
				return buildResponse("message not valid JSON", 500, "text/plain")
		else:
			return buildResponse("no message provided. Please pass a message parameter to cometd", 400)
			
		if not message.has_key("channel"):
			return buildResponse('{ "error":"invalid message passed" }', 400, "text/plain")
		
		chan = message["channel"]
		if chan == "/meta/handshake":
			# looks like we'll need to create a Connection
			return self.initHandshake(request, message)
		elif chan == "/meta/connect":
			# finish connection initialization!
			return self.connect(request, message)
		elif chan == "/meta/reconnect":
			return self.reconnect(request, message)
		elif chan == "/meta/subscribe":
			return self.subscribe(request, message)
		elif chan == "/meta/unsubscribe":
			return self.unsubscribe(request, message)
		# FIXME: implement /meta/ping and /meta/status !!

		# otherwise route the message to listeners
		return self.route(request, message)

	############################################################################
	# PROTOCOL METHODS
	############################################################################

	def initHandshake(self, request, message):
		# handle initial auth and create a Connection that subsequent
		# /meta/connect messages can talk to

		# tell the client what we can and can't support:
		resp = {
			"channel":	"/meta/handshake",
			"version":	self.version,
			"minimumVersion": self.minimumVersion,
			"supportedConnectionTypes":	SupportedConnectionTypes
		}

		client = self.checkHandshakeAuth(request, message)
		resp["clientId"] = client.id
		resp["authSuccess"] = client.authSuccess
		resp["authToken"] = client.authToken
		resp["error"] = client.lastError

		log.msg(simplejson.dumps(resp))

		return buildResponse(simplejson.dumps(resp), type="text/plain")

	# FIXME: should we look into using twisted.cred here to handle auth types?
	def checkHandshakeAuth(self, request, message):
		(success, token, error) = self.checkCredentials(request, message)

		# FIXME: mst suggests using something like md5(clientIP, localMAC, time) instead
		if success:
			client = Client(authSuccess=success, authToken=token, lastError=error)
			self.clients[client.id] = client

		return client

	def checkCredentials(self, request, message):
		# return's a tuple with the form:
		#	(success, token, error)
		# FIXME: plug in auth check here!
		return (True, None, None)

	def _sanityCheckConnection(self, request, message):
		isSane = True
		errorResp = None
		error = ""
		
		# sanity check the connection request
		if "connectionType" not in message or \
			message["connectionType"] not in SupportedConnectionTypes:
			isSane = False
			error = "invalid connectionType requested"
		elif "clientId" not in message or \
			message["clientId"] not in self.clients:
			isSane = False
			error = "invalid clientId provided"
			# log.msg(message["clientId"])
		
		if not isSane:
			resp = simplejson.dumps({ "error": str(error) })
			errorResp = buildResponse(resp, 500, "text/plain")

		return (isSane, errorResp)

	def connect(self, request, message):
		"""
		Create a new connection object for the client.
		"""
		
		(isSane, errorResp) = self._sanityCheckConnection(request, message)
		if not isSane: return errorResp

		clientId = message["clientId"]
		client = self.clients[clientId]

		(	client.authSuccess, 
			client.authToken,
			client.lastError	) = self.checkCredentials(request, message)

		if not client.authSuccess:
			# auth failure, nuke the client from the list
			del self.clients[clientId]
			resp = simplejson.dumps({ "error": client.error })
			log.msg(resp)
			return buildResponse(resp, 500, "text/plain")

		# if the request is sane and valid, set up a new Connection object
		# which will initiate the response and manage it from here on out
		conn = Connection(request, message, client)
		client.setConnection(conn)
		return buildResponse(conn.stream, type=conn.contentType)

	def reconnect(self, request, message):
		# FIXME: implement!
		(isSane, errorResp) = self._sanityCheckConnection(request, message)
		if not isSane: return errorResp

		clientId = message["clientId"]
		client = self.clients[clientId]

		(	client.authSuccess, 
			client.authToken,
			client.lastError	) = self.checkCredentials(request, message)

		if not client.authSuccess:
			# auth failure, nuke the client from the list
			del self.clients[clientId]
			resp = simplejson.dumps({ "error": client.error })
			log.msg(resp)
			return buildResponse(resp, 500, "text/plain")

		client.connection.reopen()

		return buildResponse(client.connection.stream, type=client.connection.contentType)

	def subscribe(self, request, message):
		# get the client and the channel here
		# self._subscribe()
		if "clientId" not in message or \
			message["clientId"] not in self.clients:
			resp = simplejson.dumps({ "error": "invalid clientId provided" })
			log.msg(resp)
			return buildResponse(resp, 500, "text/plain")

		client = self.clients[message["clientId"]]

		self._subscribe(client, message["subscription"])

		resp = {
			"channel":		"/meta/subscribe",
			"subscription":	message["channel"],
			"successful":	True
			# "authToken":	"SOME_NONCE"
		}

		# FIXME: should we be calling client.deliver and having *that* dispatch
		# down to the correct connection object?
		client.connection.deliver(resp)
		return buildResponse("{ success: true }", type="text/plain")

	def _subscribe(self, client, chan):
		"set up a subscription"
		# the channels data structure is a tree, relying on the speed of Python
		# dictionary lookups to quickly return a list of interested clients.
		# NOTE: we are not currently supporting the "*" glob operator in channels
		cparts = chan.split("/")[1:]
		log.msg(cparts)
		root = self.subscriptions
		for part in cparts: # FIXME: is iteration order garunteed?
			if not part in root:
				log.msg("creating part: ", part)
				root[part] = { "__cometd_subscribers": {} }
			root = root[part]

		root["__cometd_subscribers"][client.id] = client

	def route(self, request, message):
		"""
		Event routing and delivery. The guts of cometd.
		"""
		cparts = message["channel"].split("/")[1:]
		log.msg(cparts)
		root = self.subscriptions
		for part in cparts: # FIXME: is iteration order garunteed?
			if "*" in root:
				log.msg("delivering to wildcard subscribers")
				subs = root["*"]["__cometd_subscribers"]
				for client in subs:
					# FIXME: check for "openness"?
					log.msg(client)
					subs[client].connection.deliver(message)
			if not part in root:
				log.msg("no part:", part, "matches for delivery")
				return buildResponse("{ success: true }", type="text/plain")
			root = root[part]
			subs = root["__cometd_subscribers"]
			for client in subs:
				subs[client].connection.deliver(message)
		return buildResponse("{ success: true }", type="text/plain")

class CometdRunner(resource.Resource):
	# blah, hacky class. Wish we didn't need it
	addSlash = True
	log.msg("cometd initialized")
	child_tests = static.File(path("./tests").abspath())
	child_cometd = cometd()

	def render(self, ctx):
		return http.Response(200,
			{ "content-type": http_headers.MimeType('text', 'plain') },
			stream=stream.FileStream(path("cometd.txt").abspath().open())
		)


# FIXME: we don't always want to run this!? How do we tell if we're running under twistd?
# FIXME: we should be getting all of this config info from a file!!
site = server.Site(CometdRunner())
application = service.Application("cometd")
s = strports.service('tcp:8080', channel.HTTPFactory(site))
s.setServiceParent(application)

# vim:ts=4:noet:
