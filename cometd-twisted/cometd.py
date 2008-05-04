# Copyright (C) The Dojo Foundation 2006-2008
# All rights reserved.
#
# Distributed under the terms of the BSD License
#
#############################################################################
# Handy CometD I/O details:
#
# (1) There are two layers of communication happening here. 
# The first, HTTP layer, is the topmost two-way layer between the client and server.
# This layer must ALWAYS either be a 500 or a 200, or the client tries to reconnect wildly. 
# Note that the hard coded HTTP header wrapper data is in comet_js_headers.py
# 
# (2) A handshake is sent to the server first, via HTTP.
# No stream (layer 2) is established yet. The connect() request arrives at the server
# via HTTP, the stream is established, and layer 2 is up. Once layer 2 is up, 
# everything traveling from server to client must be echoed to both layers. 
# On the stream layer, raw JSON array is sent. On the HTTP layer, the same JSON array 
# is properly wrapped in a 200 (or 500 upon failure) and sent across the HTTP layer 
# (simply returned).
#
# (3) On a long-polling connection the client must repeatedly send connect() messages. 
# With long-polling,the stream is closed after each send, and must be reopened.
# Upon a connect receipt and stream reopen, all deferred messages are sent at this time. 
#
# (4) The stream I/O is only one-way, from server to client. All communication 
# from client to server is via HTTP. After a connect() is satisfied, all 
# communication from server to client is BOTH HTTP and stream, as stated in (2)
#
# (5) To be clear, # scenes, all of this communication is happening across the 
# HTTP socket that opens when a client issues a request 9such as a connect())
# to the server. All data is sent in the response, along the same socket. 
#############################################################################
import twisted
import twisted.web2
from twisted.web2 import http, resource, channel, stream, server, static, http_headers, responsecode
from twisted.python import log
from twisted.internet import reactor
from twisted.application import service, strports
from path import path
from sets import Set
import re
import os
import md5
import time
import string
import base64
import types
import weakref
import copy

import pdb
import pprint

from handy import *
setGlobalLogger(twisted.python.log)  # set this as early as possible.

import bayeux_config
import bayeux_server
import cometd_session
import comet_js_headers

'''
Testing mode reads data from an HTTP request a bit differently, and may also
have different stream requirements.
'''
global TESTING
TESTING = bayeux_config.testingMode


'''
The cleanup timer is a timer for periodic cleanup, such as
timeouts of idle connections, etc.
'''
global CleanupTimer
CleanupTimer = rightNowInUTC(bayeux_config.serverTimeZone)

"""
The cometd modules provides a twisted.web2.resource.Resource endpoint which
acts as a generalized multi-endpoint event router that speaks the cometd JSON
message format and protocol on the wire.
"""

'''
Bayeux protocol server side handling.

CometD session handling, such as making sure handshake preceed connect requests,
timing out old unconnected handshakes, etc. 
'''
global ClientStates,ClientTopics,BayeuxServer
CometClients = cometd_session.CometClients()
ClientTopics = cometd_session.CometTopics()
BayeuxServer = bayeux_server.BayeuxServer(CometClients,ClientTopics)

# constants and configuration
verbose = True

# FIXME: implement advices!
# FIXME: need to implement resource constraints

def buildResponse(request, data, code=200, type="text/html", headers={}):
	jsonpCallbackHdr = ""
	jsonpCallbackFtr = ""
	if request:
		if "jsonp" in request.args:
			jsonpCallbackHdr = request.args["jsonp"][0] + "("
			jsonpCallbackFtr = ")"

	respStream = None
	if isinstance(data, stream.ProducerStream):
		respStream = data
	else:
		if not isinstance(data,str):
			data = jsonpCallbackHdr \
				+ json_encode(data) \
				+ jsonpCallbackFtr

		respStream = stream.MemoryStream(data)

	thead = http_headers.Headers()
	thead.addRawHeader("Content-Type", type)
	for name in headers:
		thead.addRawHeader(name, headers[name])

	return http.Response(code, stream=respStream, headers=thead)


SupportedFixedTopics = {
	"/meta/handshake":		BayeuxServer.Handshake,
	"/meta/connect":		BayeuxServer.Connect,
	"/meta/disconnect":		BayeuxServer.Disconnect,
	"/meta/publish":		BayeuxServer.Publish,
	"/meta/subscribe":		BayeuxServer.Subscribe,
	"/meta/unsubscribe":	BayeuxServer.Unsubscribe,
	"/service/urlService":	BayeuxServer.Service
}

class cometd(resource.PostableResource):

	def __init__(self):
		pass

	# FIXME: we need to implement client culling. Choices are between keeping a
	# list of update times and associated clients, but that requires a
	# per-message, per-client update to the state to remove the clients from
	# the "below the line" state.

	def locateChild(self, request, segments):
		# when we're reached, switch immediately to render mode
		if verbose:
			log.msg(request)
			log.msg(segments)
		return (self, server.StopTraversal)

	def render(self, request):
		global CleanupTimer

		'''
		Do some cleanup every N minutes.
		'''
		rightnow = rightNowInUTC(bayeux_config.serverTimeZone)
		if (CleanupTimer + datetime.timedelta(minutes=bayeux_config.cleanupTimer) <= rightnow):
			'''
			This will disconnect clients who are idle for too long (bayeux_config.py)
			'''
			CometClients.checkActivityTimers()
			CleanupTimer = rightnow
		
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
		if request.args.has_key("tunnelInit") and \
			comet_js_handlers.ConnectionTypes.has_key(request.args["tunnelInit"][0]):

			resp = comet_js_handlers.ConnectionTypes[request.args["tunnelInit"][0]]["tunnelInit"]
			if verbose: log.msg(resp)
			return buildResponse(None,resp)

		# otherwise if we got a "message" parameter, deserialize it

		'''
		In Testing mode, the args parameter does not get set 
		(this is a documented Twisted
		bug, never fixed, happens when data is sent via httplib).
		Request data arrives as part of the header, and gets 
		transfered to the args parameter here.
		'''
		if not request.args.has_key("message") and TESTING:
			request.args['message'] = []
			for i in request.headers._raw_headers["message"]:
				request.args['message'].append(i[2:-2])

		if request.args.has_key("message"):
			try:
				# log.msg(request.args["message"][0])
				messages = json_decode(request.args["message"][0])
			except ValueError:
				if verbose: log.msg("message parsing error")
				return buildResponse(None,
									"message not valid JSON", 
									comet_js_headers.errorHttpCode, 
									"text/plain")
		else:
			return buildResponse(None, 
								"No message provided. Pass a message parameter to CometD", 
								400)

		ctr = 0
		if verbose: log.msg("messages:", type(messages), ":",  json_encode(messages))
		while len(messages):
			if verbose: log.msg(len(messages))
			m = messages.pop(0)
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

			#pdb.set_trace()
			value = None
			if SupportedFixedTopics.has_key(m["channel"]):
				log.msg("Channel: >>>>%s<<<<" % m["channel"])
				value = SupportedFixedTopics[m["channel"]](m,request)
				if isinstance(value, dict):
					if value.has_key("error"):
						if value["error"]:
							log.msg("Response is: %s" % pprint.pformat(value))
							return buildResponse(request, value, 500, "text/plain")
					if m.has_key("clientId"):
						if CometClients.connectedClients.has_key(m["clientId"]):
							CometClients.connectedClients[m["clientId"]].deliver(value)
							CometClients.setActivityTimer(m["clientId"])
						else:
							value["error"] = 'true'
							log.msg("Throwing away message %s for client %s, \
								client has disconnected." % (pprint.pformat(value),m["clientId"]))
							return buildResponse(request, 
								'Message from invalid or disconnected client, ignored', 
								500, "text/plain")
					resp.append(value)
				else:
					resp = value
					break

				log.msg("Response is: %s" % pprint.pformat(resp))

			else:
				'''
				Otherwise we're publishing. Route the message to listeners

				If implicit subscribe via publish is allowed, do it.
				Only do it for connected clients (handshake/connect).
				'''
				if CometClients.connectedClients.has_key(m["clientId"]):
					log.msg("Routing: %s" % m)
					value = BayeuxServer.Publish(m,request)
					if value.has_key("error"):
						if value["error"]:
							log.msg("Response is: %s" % pprint.pformat(value))
							return buildResponse(request, value, 500, "text/plain")
					# Don't append this value. We fabricated this call.
					#resp.append(value)
					value = self.route(request, m)
					resp.append(value)
				else:
					log.msg("Throwing away message %s from client %s for other clients, \
						originating client has disconnected." % (pprint.pformat(resp),m["clientId"]))
					return buildResponse(request, 'Message from invalid client, ignored', 
						500, "text/plain")

			# FIXME: implement /meta/ping and /meta/status !!
			ctr += 1

		# FIXME: 
		#		need to determine here if/how we should be delivering back on
		#		an open connection channel if one was pre-existing
		if resp != []:
			return buildResponse(request, resp, type="text/plain")

	############################################################################
	# PROTOCOL METHODS
	############################################################################

	def initHandshake(self, request, message):
		# handle initial auth and create a Connection that subsequent
		# /meta/connect messages can talk to

		resp = BayeuxServer.Handshake(message)

		log.msg("CLIENT MSG:==> %s ==> %s" % (pprint.pformat(request),pprint.pformat(message)))
		rstr = [ resp ]
		if verbose: log.msg("initHandshake response:", rstr)

		return buildResponse(request, rstr, type="text/plain")

	def _sanityCheckConnection(self, request, message):
		isSane = True
		errorResp = None
		error = ""

		# sanity check the connection request
		if not message.has_key("clientId") or \
			not CometClients.connectedClients.has_key(message["clientId"]):
			isSane = False
			error = "invalid clientId provided"
			# log.msg(message["clientId"])
		elif "connectionType" not in message or \
			message["connectionType"] not in SupportedConnectionTypes:
			isSane = False
			error = "invalid connectionType requested"
		if not isSane:
			resp = json_encode({ "error": str(error) })
			errorResp = buildResponse(request, resp, 500, "text/plain")

		return errorResp

	def connect(self, request, message):
		error =  self._sanityCheckConnection(request, message)
		if error:
			return error
		CometClients.connectedClients[message["clientId"]].reconnect()

	def route(self, request, message):
		"""
		Event routing and delivery. The guts of cometd.
		"""
		error = self._sanityCheckConnection(request, message)

		result = { 
			"successful": True,
			"channel": message["channel"]
		}
		if message.has_key("id") and message["id"] is not None:
			result["id"] = message["id"]

		'''
		All possible glob combos for single and double globbing 
		are pre-expanded in this dict. 
		The key = the match, the value = array of exact match topics.
		'''

		already_sent = Set([])
		message_topic = message["channel"]

		'''
		Topic patterns aren't stored with trailing slashes.
		Make sure the message topic does not have one either. 
		'''
		if message_topic[-1] == '/':
			message_topic = copy.deepcopy(message["channel"])
			message_topic = message_topic[0:-1]

		'''
		Keep track of clients whom have already received this message.
		The same clients could be listed in more than one glob match. 
		'''
		if ClientTopics.quickTopicMatch.activeTopics.has_key(message_topic):
			no_match = []
			for client_id in ClientTopics.quickTopicMatch.activeTopics[message_topic]:
				if (client_id not in already_sent) and (CometClients.connectedClients.has_key(client_id)):
					CometClients.connectedClients[client_id].deliver(message)
					'''
					One problem with this timer: It is reset upon send and receive. 
					There is currently no tracking for clients who have only received
					but had sent nothing for some period of time. 
					'''
					CometClients.setActivityTimer(client_id)
					already_sent.add(client_id)
				else:
					no_match.append(client_id)

			if no_match != []:
				result["successful"] = False
				result["error"] = [1401,BayeuxServer.json_errcodes[1401] % (message_topic)]
				if verbose: log.msg("Program Error: these client_ids are in the quick Topic match, \
					but are not connected: %s, the message was not routed." % \
					pprint.pformat(no_match))
				
			return result

		result["successful"] = False
		result["error"] = [1301,BayeuxServer.json_errcodes[1301] % (message_topic)]
		if verbose: log.msg("No topic:", message_topic, 
			"matches for delivery. If implicit subscribe is allowed, it will be done now.")
		return result

		# FIXME: aggregate ACKs!

# vim:ts=4:noet:
