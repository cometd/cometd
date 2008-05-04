#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
# Comet state and topic handling.
#
# GloriaJW. 2008_01_06
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
import pdb
import datetime,random,string
import pprint
from sets import Set

from twisted.web2 import stream, http_headers, http
import bayeux
import bayeux_config
import comet_js_headers
from handy import *

#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
# Global functions
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
# def implicitGroupName():
# 	'''
# 	Six random letters followed by a timestamp string.
# 	'''
# 	#chars=string.letters + string.digits
# 	chars=string.letters
# 	return "%s%s" % (''.join([random.choice(chars) for i in xrange(6)],
# 		rightNowInUTC(bayeux_config.serverTimeZone).strftime("%Y%d%m%H%M%S")))

def generateMessageId():
	return (''.join([random.choice(string.digits) for i in xrange(10)]))
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
# Generic classes
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
class Singleton(object):
    def __new__(type):
        if not '_the_instance' in type.__dict__:
            type._the_instance = object.__new__(type)
        return type._the_instance

#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
# Data containers
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
class HandshakeState:
	def __init__(self,client_id,auth_type):
		self.client_id = client_id
		self.auth_type = auth_type
		self.timestamp = rightNowInUTC(bayeux_config.serverTimeZone)

class ConnectedState:
	def __init__(self,client_id,auth_type,connection_type,request=None):
		self.client_id = client_id
		self.auth_type = auth_type
		self.connection_type = connection_type
		self.timestamp = rightNowInUTC(bayeux_config.serverTimeZone)
		self.lastActivity = rightNowInUTC(bayeux_config.serverTimeZone)
		#self.stream = stream.ProducerStream()
		'''
		Don't del this stream, or reset it to None.
		Let it go out of scope naturally. Twisted may be holding a reference
		to it that may not be apparent/visible to you. 
		'''
		self.stream = None
		self.ctypeProps = comet_js_headers.ConnectionTypes[self.connection_type]
		self.contentType = self.ctypeProps["contentType"]
		self.jsonpCallbackHdr = ""
		self.jsonpCallbackFtr = ""
		self.messageBacklog = []

		if request:
			'''
			If we have an HTTP request, send this preamble upon connection.
			'''
			self.stream = stream.ProducerStream()
			self.stream.write(self.ctypeProps["preamble"])
			self.checkCallbackPolling(request)
			if self.ctypeProps["closeOnDelivery"]:
				self.stream.finish()
				#self.stream = None

	#~~~~~~~~~~~~
	def __del__(self):
		GlobalLogger.debug("Closing stream.",debug=True)
		try:
			self.stream.finish()
		except:
			pass
	#~~~~~~~~~~~~
	def checkCallbackPolling(self,request):
		'''
		Check for callback-polling (JSONP on a cross-domain cometd server)
		'''
		if request:
			if "jsonp" in request.args:
				self.jsonpCallbackHdr = request.args["jsonp"][0] + "("
				self.jsonpCallbackFtr = ");"

	#~~~~~~~~~~~~
	def reconnect(self,request=None,message=None):
		'''
		Don't resend preamble upon reconnect?
		'''
		if self.stream:
			if not self.stream.closed:
				self.stream.finish()

		self.stream = stream.ProducerStream()

		self.checkCallbackPolling(request)
		self.deliver(message)

	#~~~~~~~~~~~~
	def deliver(self,message=None):
		if message:
			if isinstance(message,list):
				(self.messageBacklog.append(x) for x in message)
			else:
				self.messageBacklog.append(message)

		if not self.stream:
			self.stream = stream.ProducerStream()

		elif self.stream.closed:
			GlobalLogger.warn("Queueing messages for client %s, stream is closed! \
				Will reconnect and send upon next connect request from client." % self.client_id)
			return False

		'''
		Send entire message array as one chunk.
		'''
		if self.messageBacklog != []:
			GlobalLogger.info("Delivering messages for client %s: %s" % \
				(self.client_id,self.messageBacklog))
			outbound = self.jsonpCallbackHdr \
								+ self.ctypeProps["envelope"] % json_encode(self.messageBacklog) \
								+ self.jsonpCallbackFtr
			self.stream.write(outbound)

			self.messageBacklog = []

			'''
			Only close the stream here if we've used it here.
			Otherwise leave it open. The HTTP buildResponse will make use of it.
			'''
			if self.ctypeProps["closeOnDelivery"]:
				self.stream.finish()

		return True

	#~~~~~~~~~~~~
	def buildResponse(self,request, data, code=200, type="text/html", headers={}):
		jsonpCallbackHdr = self.jsonpCallbackHdr
		jsonpCallbackFtr = self.jsonpCallbackFtr
		if request:
			if "jsonp" in request.args:
				jsonpCallbackHdr = request.args["jsonp"][0] + "("
				jsonpCallbackFtr = ");"

		respStream = None
		if isinstance(data, stream.ProducerStream):
			respStream = data
		else:
			if isinstance(data,str):
				data = jsonpCallbackHdr \
					+ json_encode(data) \
					+ jsonpCallbackFtr

			respStream = stream.MemoryStream(data)

		thead = http_headers.Headers()
		thead.addRawHeader("Content-Type", type)
		for name in headers:
			thead.addRawHeader(name, headers[name])

		return http.Response(code, stream=respStream, headers=thead)

class QuickTopicMatch(Singleton):
	#~~~~~~~~~~~~
	def __init__(self):
		self.activeTopics = {}

	#~~~~~~~~~~~~
	def registerTopic(self,topic,client_id):
		'''
		Array of exact topic match to clients.
		'''
		if not self.activeTopics.has_key(topic.channel):
			self.activeTopics[topic.channel] = Set([])
		if not client_id in self.activeTopics[topic.channel]:
			self.activeTopics[topic.channel].add(client_id)
			GlobalLogger.info("registerTopic: adding client ID %s for topic %s" % \
				(client_id,topic.channel))

		'''
		Array of all clients subscribed to channels which match this wildcard topic.
		'''
		if not self.activeTopics.has_key(topic.single_wild):
			self.activeTopics[topic.single_wild] = Set([])
		if not client_id in self.activeTopics[topic.single_wild]:
			self.activeTopics[topic.single_wild].add(client_id)
			GlobalLogger.info("registerTopic: adding client ID %s for topic %s" % \
				(client_id,topic.single_wild))

		'''
		Array of all clients subscribed to channels matching the double wildcard.
		'''
		for dwild in topic.double_wild.keys():
			if not self.activeTopics.has_key(dwild):
				self.activeTopics[dwild] = Set([])
			if not client_id in self.activeTopics[dwild]:
				self.activeTopics[dwild].add(client_id)
				GlobalLogger.info("registerTopic: adding client ID %s for topic %s" % \
					(client_id,dwild))

	#~~~~~~~~~~~~
	def removeTopic(self,topic,client_id):

		try:
			self.activeTopics[topic.channel].remove(client_id)
		except KeyError:
			pass
		if len(self.activeTopics[topic.channel]) == 0:
			del self.activeTopics[topic.channel]

		try:
			self.activeTopics[topic.single_wild].remove(client_id)
		except KeyError:
			pass
		if len(self.activeTopics[topic.single_wild]) == 0:
			del self.activeTopics[topic.single_wild]

		for dwild in topic.double_wild.keys():
			try:
				self.activeTopics[dwild].remove(client_id)
			except KeyError:
				pass
			if len(self.activeTopics[dwild]) == 0:
				del self.activeTopics[dwild]


class Topic: # also known as Channel
	#~~~~~~~~~~~~
	def __init__(self,client_id,channel,owner=None,implicit=False):
		self.owner = owner # True if client id is the owner/originator of the topic.
		self.client_id = client_id
		self.channel = channel

		subchannels = self.channel.split('/')[1:]
		self.single_wild = '/' + '/'.join(s for s in subchannels[0:-1]) + '/*'

		self.double_wild = {}
		dlength = (len(subchannels)-1 if len(subchannels) > 1 else 1)

		for i in xrange(1,dlength+1):
			self.double_wild['/' + '/'.join(s for s in subchannels[0:i]) + '/**'] = self.channel
		
		'''
		Implicit means it was created by a subscrible, not a publish,
		and it has no owner (anyone can destroy it).
		'''
		self.implicit = implicit 

		self.timestamp = rightNowInUTC(bayeux_config.serverTimeZone)

#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
# CometD Topic (Channel) Management Classes
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
class CometTopics(Singleton):
	def __init__(self):
		self.topicsByClient = {} #  client_id, topic_name
		self.clientsByTopic = {} #  topic_name, client_id
		self.quickTopicMatch = QuickTopicMatch() #  topics and all wildcard expansions

	#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	# Topic (Channel) Handling
	#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	def publishTopic(self,topic_names,client_id):
		return self.registerTopics(topic_names,client_id)

	def registerTopics(self,topic_names,client_id,implicit=False):

		new_topics = []
		for topic_name in topic_names:

			GlobalLogger.debug("Published topic %s for client %s" % \
				(topic_name,client_id),debug=True)
	
			existing_topic = self.validateTopic(topic_name,client_id)
			if existing_topic:
				new_topics.append(existing_topic)
				continue

			new_topic = Topic(client_id,topic_name,client_id,implicit)
	
			if not self.topicsByClient.has_key(client_id):
				self.topicsByClient[client_id] = {}
	
			if not self.topicsByClient[client_id].has_key(topic_name):
				self.topicsByClient[client_id][topic_name] = {}
	
			self.topicsByClient[client_id][topic_name] = new_topic
		
			if not self.clientsByTopic.has_key(topic_name):
				self.clientsByTopic[topic_name] = {}
	
			self.clientsByTopic[topic_name][client_id] = new_topic
	
			self.quickTopicMatch.registerTopic(new_topic,client_id)

			new_topics.append(new_topic)
	
		return new_topics

	#~~~~~~~~~~~~~~~~
	def removeTopic(self,topic_name,client_id):
		try:
			owner_id = self.clientsByTopic[topic_name][client_id].owner
			implicit = self.clientsByTopic[topic_name][client_id].implicit
			if (owner_id == client_id) or implicit:
				self.quickTopicMatch.removeTopic(self.clientsByTopic[topic_name][client_id],
					client_id)
				del self.clientsByTopic[topic_name][client_id]
				del self.topicsByClient[client_id][topic_name]
				return True
			'''
			Cannot remove if there is an owner, and you're not it.
			'''
			return False 

		except (IndexError,KeyError):
			GlobalLogger.debug("removeTopic: nonexistent topic, ignoring.",debug=True)
			return False 

	#~~~~~~~~~~~~~~~~
	def removeClient(self,client_id):
		for topic_name in self.clientsByTopic.keys():
			for c_id in self.clientsByTopic[topic_name].keys():
				if c_id == client_id:
					self.removeTopic(topic_name,c_id)
		for c_id in self.topicsByClient.keys():
			if c_id == client_id:
				del self.topicsByClient[c_id] 


	#~~~~~~~~~~~~~~~~
	def validateTopic(self,topic_name,client_id):
		try:
			return self.clientsByTopic[topic_name][client_id]
		except (IndexError,KeyError):
			return None
	#~~~~~~~~~~~~~~~~
	def alreadySubscribed(self,topic_name,client_id):
		try:
			existing_topic = self.clientsByTopic[topic_name].values()[0]
		except:
			return False

		if self.topicsByClient.has_key(client_id):
			return True

	#~~~~~~~~~~~~~~~~
	def subscribeToTopic(self,topic_name,client_id):
		'''
		Topics are the same for new client as they are for an existing client,
		if topic name matchs.
		'''
		if self.alreadySubscribed(topic_name,client_id):
			return True

		try:
			existing_topic = self.clientsByTopic[topic_name].values()[0]
		except:
			GlobalLogger.debug("Nonexistent topic, ignoring.",debug=True)
			return False

		if not self.topicsByClient.has_key(client_id):
			self.topicsByClient[client_id] = {}

		if not self.topicsByClient[client_id].has_key(topic_name):
			self.topicsByClient[client_id][topic_name] = {}

		self.topicsByClient[client_id][topic_name] = existing_topic
		
		if not self.clientsByTopic[topic_name].has_key(client_id):
			self.clientsByTopic[topic_name][client_id] = existing_topic

		GlobalLogger.debug("Client %s subscribed to topic %s" % (client_id,topic_name),debug=True)
		return True

	#~~~~~~~~~~~~~~~~
	def unsubFromTopic(self,topic_name,client_id):
		try:
			del self.clientsByTopic[topic_name][client_id]
			del self.topicsByClient[client_id][topic_name]

		except:
			pass

		GlobalLogger.debug("Client %s unsubscribed from topic %s" % (client_id,topic_name),
			debug=True)
		return True

	#~~~~~~~~~~~~~~~~
	def prepareToDeliver(self,data,client_id,topic_name,guaranteed_delivery=False):
		deliver_messages = []

		try:
			all_subscribers = self.clientsByTopic[topic_name].keys()
		except (KeyError,IndexError):
			GlobalLogger.debug("Nonexistent topic for delivery, ignoring.",debug=True)
			return False

		'''
		Guaranteed delivery is sent out with the rest of the channel messages.
		Right now, Guaranteed delivery is not true "end-to-end", which would require
		an ACK from each subscriber, before sending the ACK back to the sender. 
		'''
		for c_id in self.clientsByTopic[topic_name].keys():
			if c_id == client_id:
				if guaranteed_delivery:
					deliver_messages.append(
						{"channel" : topic_name,
							"clientId" : c_id,
							"id" : generateMessageId(),
							"successful" :"true",
							"ext" :{"guaranteedDelivery":"true"}
						}
					)
				'''
				KLUDGE: send message back to the sender as well. 
				'''
				deliver_messages.append(
					{"channel" : topic_name,
						"clientId" : c_id,
						"id" : generateMessageId(),
						"data" : data
					}
				)
			else:
				deliver_messages.append(
					{"channel" : topic_name,
						"clientId" : c_id,
						"id" : generateMessageId(),
						"data" : data
					}
				)

		return deliver_messages
		
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
# CometD Session Management Classes
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
class CometClients(Singleton):
	def __init__(self):
		self.handshakeClients = {}
		self.connectedClients = {}

	#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	# Handshake handling
	#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	def registerHandshake(self,client_id,auth_type):
		self.handshakeClients[client_id] = HandshakeState(client_id,auth_type)
		GlobalLogger.debug("Cometd Clients: %s" %  pprint.pformat(self.handshakeClients),debug=True)

	def removeHandshake(self,client_id):
		try:
			m = self.handshakeClients[client_id]
			del self.handshakeClients[client_id]
			return m
		except:
			return None

	'''
	Check if existing handshake timestamps have expired 
	(should run in it's own thread).
	'''
	def checkHandshakeTimers(self,client_id=None):
		'''
		A bit of grace in exchange for time.

		Ensure that it's in UTC 0. 
		'''
		rightnow = rightNowInUTC(bayeux_config.serverTimeZone)
		if client_id:
			try:
				if (self.handshakeClients[client_id].timestamp + \
						datetime.timedelta(milliseconds=bayeux_config.handshakeTimeout) <= rightnow):
					del self.handshakeClients[client_id]
					GlobalLogger.warn("CheckHandshakeTimers: Dropping client %s, \
						timed out without connect." % self.handshakeClients[client_id])
					return False
				return True

			except (KeyError,IndexError):
				return False # client never handshook.

		for c in handshakeClients:
			if (c.timestamp + datetime.timedelta(milliseconds=bayeux_config.handshakeTimeout) \
					<= rightnow):
				del c
				GlobalLogger.warn("CheckHandshakeTimers: Dropping client %s, \
					timed out without connect." % c.clientId)

	#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	# Connect handling
	#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	def registerConnect(self,client_id,auth_type,connection_type):
		self.connectedClients[client_id] = ConnectedState(client_id,auth_type,connection_type)
		GlobalLogger.debug("Cometd Connected Clients: %s " % \
			pprint.pformat(self.connectedClients),debug=True)
		return self.connectedClients[client_id].timestamp
		
	#~~~~~~~~~~~~~~~~
	def alreadyConnected(self,client_id):
		GlobalLogger.debug("Cometd Already Connected Clients: %s" % \
			pprint.pformat(self.connectedClients),debug=True)
		try:
			return self.connectedClients[client_id]
		except:
			return None
		
	#~~~~~~~~~~~~~~~~
	def disconnect(self,client_id):
		try:
			del self.connectedClients[client_id]
			GlobalLogger.debug("Disconnected client %s" % client_id,debug=True)
		except (KeyError,IndexError):
			GlobalLogger.debug("Client %s not connected, ignored disconnect request." % \
				client_id,debug=True)
		
	#~~~~~~~~~~~~~~~~
	def validate(self,client_id):
		if self.connectedClients.has_key(client_id):
			return True
		return False

	#~~~~~~~~~~~~~~~~
	def setActivityTimer(self,client_id):
		try:
			self.connectedClients[client_id].lastActivity = \
				rightNowInUTC(bayeux_config.serverTimeZone)
		except:
			GlobalLogger.error("setActivityTimers: Client %s was not connected. \
				Where did you get this client id from?" % self.connectedClients[client_id])

	#~~~~~~~~~~~~~~~~
	'''
	Check if clients are inactive for too long.
	'''
	def checkActivityTimers(self,client_id=None):
		'''
		A bit of grace in exchange for time.

		Ensure that it's in UTC 0. 
		'''
		rightnow = rightNowInUTC(bayeux_config.serverTimeZone)
		if client_id:
			try:
				if (self.connectedClients[client_id].lastActivity + \
					datetime.timedelta(minutes=bayeux_config.longPollingTimeout) <= rightnow):
					self.disconnect(client_id)
					GlobalLogger.warn("CheckActivityTimers: Dropping client %s, timed out due \
						to inactivity." % self.connectedClients[client_id])
					return False
				return True

			except (KeyError,IndexError):
				return False # client never connected.

		to_disconnect = []
		for client_id in self.connectedClients:
			if (self.connectedClients[client_id].lastActivity + \
				datetime.timedelta(minutes=bayeux_config.longPollingTimeout) <= rightnow):
				to_disconnect.append(client_id)
				GlobalLogger.warn("CheckActivityTimers: Dropping client %s, timed out due to \
					inactivity." % client_id)

		for client_id in to_disconnect:
			self.disconnect(client_id)
# vim:ts=4:noet:
