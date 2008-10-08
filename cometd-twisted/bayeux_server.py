#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
# Bayeux server protocol handling.
# Note that for test and learning purposes, there are both client and 
# server implementations present.
#
# GloriaJW. 2008_01_04
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
import subprocess,urllib2,pprint
from sets import Set
import pdb

from bayeux import *
from handy import *
import bayeux_config
import cometd_session

#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
# Class definitions
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
class BayeuxServer:
	def __init__(self,comet_clients_state, comet_topics_state):

		self.cometClients = comet_clients_state
		self.cometTopics = comet_topics_state

		self.json_structure, self.json_errcodes = import_bayeux_structures()

		self.connectionTypesSet = Set(self.json_structure["handshake"]["supportedConnectionTypes"])

		authtype = self.json_structure["handshake"]["advice"]["authenticate"]["authtype"]
		self.configAuthTypes = {
								"Auto":Set(authtype),
								"auto":Set(authtype),
								"AUTO":Set(authtype),
								"htpasswd":Set(authtype[0]),
								"OpenId":Set(authtype[1])
								}

	#~~~~~~~~~~~~~~~~~~
	#@json_unwrap
	def ServerInitHandshake(self):
		handshake = self.json_structure["handshake"]

		GlobalLogger.debug("BayeuxServer: In Self-Init Handshake...",debug=True)

		return {"channel" : handshake["channel"],
				"version" : handshake["version"],
				"minimumVersion" : handshake["minimumVersion"],
				"supportedConnectionTypes" : handshake["supportedConnectionTypes"],
				"successful" : handshake["successful"][0], # "true"
				"authSuccessful" : handshake["authSuccessful"][0], # "true"
				"advice" :  {"reconect":handshake["advice"]["reconnect"][0], # "retry"
							"authenticate":handshake["advice"]["authenticate"]["authtype"][0] # "htpasswd"
					}
				}
	#~~~~~~~~~~~~~~~~~~
   	#@json_unwrap
	def Handshake(self, clientHandshake, request = None):
		error = None

		GlobalLogger.debug("BayeuxServer: In Handshake...",debug=True)
		try:
			clientConnectionTypesSet = Set([str(t) for t in clientHandshake['supportedConnectionTypes']])
		except:
			error = [1001,self.json_errcodes[1001] % "(Blank)"]

		if not error:
			if clientHandshake["channel"] != self.json_structure["handshake"]["channel"]:
				error = [801,self.json_errcodes[801] % (clientHandshake["channel"])]
	
			elif check_version(clientHandshake["version"]) == 2:
				'''
				Version type checking.
				'''
				try:
					if check_version(clientHandshake["minimumVersion"]) == 2:
						error = [901,self.json_errcodes[901] % \
							(clientHandshake["minimumVersion"],BAYEUX_VERSION)]
				except KeyError:
					error = [902,self.json_errcodes[902] % (clientHandshake["version"])]
	
			elif not clientConnectionTypesSet.intersection(self.connectionTypesSet):
				'''
				Connection type checking.
				'''
	
				'''
				difference = members in client list but not in server list. This should be null,
				or the client has an invalid value.
				'''
				error = [1001,self.json_errcodes[1001] % \
					(clientHandshake["supportedConnectionTypes"])]
	
			'''
			Login checking: If required by the config file (loginRequired is not None), 
			it's mandatory here. 
			'''
			if bayeux_config.loginRequired:
				try:
					authMethods = self.configAuthTypes[bayeux_config.loginRequired]
				except (KeyError,IndexError):
					error = [601,self.json_errcodes[601] % \
						(bayeux_config.loginRequired,
						self.json_structure["handshake"]["advice"]["authenticate"]["authtype"])]
					authMethods = None
	
				if not error:
					if not clientHandshake.has_key("ext"):
						error = [1103,self.json_errcodes[1103]]
		
					elif not clientHandshake["ext"].has_key("authenticate"):
						error = [1103,self.json_errcodes[1103]]
		
					elif not clientHandshake["ext"]["authenticate"].has_key("authtype"):
						error = [1103,self.json_errcodes[1103]]
		
					elif clientHandshake["ext"]["authenticate"]["authtype"] not in authMethods:
						error = [1101,self.json_errcodes[1101] % \
							clientHandshake["ext"]["authenticate"]["authtype"]]
			else:
				authMethods = None
	
			'''
			Try to log in, if appropriate.
			'''
			authtype = None
	
			if authMethods and clientHandshake.has_key("ext"):
				if clientHandshake["ext"].has_key("authenticate"):
					authtype = clientHandshake["ext"]["authenticate"]["authtype"]
					username = clientHandshake["ext"]["authenticate"]["user"]
					password = clientHandshake["ext"]["authenticate"]["password"]
				if authtype not in authMethods:
					error = [1101,self.json_errcodes[1101] % authtype]
				elif authtype == "htpasswd":
					if htpasswdAuthenticate(username,password) != True:
						error = [1102,self.json_errcodes[1102]]
					else:
						GlobalLogger.debug("BayeuxServer: Successful login/password for client.",
							debug=True)
				elif authtype == "OpenId":
					if openIdAuthenticate(username,password) != True:
						error = [1102,self.json_errcodes[1102]]
					else:
						GlobalLogger.debug("BayeuxServer: Successful login/password for client.",
							debug=True)
	
		if error:
			return {
					"channel" : self.json_structure["handshake"]["channel"],
					"version" : clientHandshake["version"],
					"successful" : self.json_structure["handshake"]["successful"][1], # "false"
					"error" : error,
					"supportedConnectionTypes" : \
						self.json_structure["handshake"]["supportedConnectionTypes"],
					"advice" :  {"reconect": \
						self.json_structure["handshake"]["advice"]["reconnect"][1] # "handshake"
					}
				}

		else:
			'''
			Track handshake clients. A timer starts for them to connect. 
			'''
			client_id = generate_random_client_id()
			self.cometClients.registerHandshake(client_id,authtype)

			handshake = self.json_structure["handshake"]

			return {
					"channel" : handshake["channel"],
					"version" : bayeux_config.version,
					"supportedConnectionTypes" : self.json_structure["handshake"]["supportedConnectionTypes"],
					"clientId" : client_id,
					"successful" : handshake["successful"][0], # "true"
					"authSuccessful" : handshake["authSuccessful"][0], # "true"
					"advice" :  {"reconect":handshake["advice"]["reconnect"][0], # "retry"
					"authenticate":handshake["advice"]["authenticate"]["authtype"][0] # "htpasswd"
						}
					}

	#~~~~~~~~~~~~~~~~~~
   	#@json_unwrap
	def Connect(self,clientConnect,request = None):
		error = None
		GlobalLogger.debug("BayeuxServer: In Connect...",debug=True)

		clientId = clientConnect["clientId"]
		if clientConnect["channel"] != self.json_structure["connect"]["channel"]:
			error = [1002,self.json_errcodes[1002] % (clientConnect["channel"])]

		elif clientConnect["connectionType"] not in self.connectionTypesSet:
			error = [1001,self.json_errcodes[1001] % (clientConnect["connectionType"])]

		else:
			'''
			This really returns a clientConnect, but the structs are similar enough
			for polymorphism in this particular case.
			'''
			already_connected = True
			clientHandshake = self.cometClients.alreadyConnected(clientId)

			'''
			If this client was already handshook and connected, this is a request to
			reopen the stream. 
			'''
			if clientHandshake:	
				self.cometClients.connectedClients[clientId].reconnect(request)
				return clientHandshake.stream
			else:	
				already_connected = False

				if not self.cometClients.checkHandshakeTimers(clientId):
					error = [1104,self.json_errcodes[1104] % (clientId)]
				else:	
					clientHandshake = self.cometClients.removeHandshake(clientId)

			if not error:
				if not clientHandshake:
					error = [1104,self.json_errcodes[1104] % (clientId)]
		
				elif not already_connected:
					timestamp = self.cometClients.registerConnect(clientId,
									clientHandshake.auth_type,
									clientConnect["connectionType"])
				else:
					timestamp = clientHandshake.timestamp

		if error:
			return {"channel" : self.json_structure["connect"]["channel"],
					"successful" : self.json_structure["handshake"]["successful"][1], # "false"
					"error" : error,
					"supportedConnectionTypes" : \
						self.json_structure["handshake"]["supportedConnectionTypes"],
					"advice" :  {"reconect": \
						self.json_structure["handshake"]["advice"]["reconnect"][1] # "handshake"
					}
				}
	
		return {
				"channel" : self.json_structure["connect"]["channel"],
				"clientId" : clientId,
				"error" : None,
				"authtoken" : None,
				"successful" : self.json_structure["connect"]["successful"][0], # "true"
				"timestamp": timestamp.strftime("%Y-%m-%dT%H:%M:%S %Z%z"),
				"advice" :  {"reconect":self.json_structure["connect"]["advice"]["reconnect"][0]} # "retry"
		}

	#~~~~~~~~~~~~~~~~~~
   	#@json_unwrap
	def Disconnect(self,clientConnect, request = None):

		if clientConnect["channel"] != self.json_structure["disconnect"]["channel"]:
			error = [1002,self.json_errcodes[1002] % (clientConnect["channel"])]

		clientId = clientConnect["clientId"]

		clientRef = self.cometClients.alreadyConnected(clientId)

		if clientRef:
			self.cometClients.disconnect(clientId)
			self.cometTopics.removeClient(clientId)
			return {
					"channel" : self.json_structure["disconnect"]["channel"],
					"successful" : self.json_structure["disconnect"]["successful"][0] # "true"
				}

		return {
				"channel" : self.json_structure["disconnect"]["channel"],
				"successful" : self.json_structure["disconnect"]["successful"][1] # "false"
			}

   #~~~~~~~~~~~~~~~~~~~~~~~
	#@json_unwrap
	def PrepareToDeliver(self, clientDeliver):

		topic = self.cometTopics.validateTopic(clientDeliver["channel"],
									clientDeliver["clientId"])

		if not topic:
			error = [1203,self.json_errcodes[1203] % (clientDeliver["clientId"])]
			return None
		else:
			if clientDeliver.has_key("ext") and \
				clientDeliver["ext"].has_key("guaranteedDelivery") and \
				clientDeliver["ext"]["guaranteedDelivery"] == "true":

				guaranteed_delivery = True

			else:
				guaranteed_delivery = False

			messages = self.cometTopics.prepareToDeliver(
											clientDeliver["data"],
											clientDeliver["clientId"],
											clientDeliver["channel"],
											guaranteed_delivery)

			return messages
    #~~~~~~~~~~~~~~~~~~~~~~~
	#@json_unwrap
	def Publish(self, clientPublish, request = None):
		error = None

		GlobalLogger.debug("BayeuxServer: In Publish, received: %s" % \
			pprint.pformat(clientPublish),debug=True)

		if not self.cometClients.validate(clientPublish["clientId"]):
			error = [1105,self.json_errcodes[1105] % (clientPublish["clientId"])]

		else:
			if isinstance(clientPublish["channel"],list):
				rList = clientPublish["channel"]
			else:
				rList = [clientPublish["channel"]]

			new_topics = self.cometTopics.registerTopics(rList,clientPublish["clientId"])
			if new_topics == []:
				error = [1204,self.json_errcodes[1204] % (clientPublish["channel"])]

			'''
			Assume that all clients who register a topic also want to subscribe to it.
			'''
			for topic in new_topics:
				if not self.cometTopics.subscribeToTopic(topic.channel,clientPublish["clientId"]):
					error = [1206,self.json_errcodes[1206] % (clientPublish["channel"])]

		if error:
			return {	
					"channel" : self.json_structure["subscribe"]["channel"],
					"clientId" : clientPublish["clientId"], # from handshake w/server
					"successful" : self.json_structure["subscribe"]["successful"][1], # "false"
					"error" : error
			}
		return {
				"channel" : self.json_structure["subscribe"]["channel"],
				"clientId" : clientPublish["clientId"], # from handshake w/server
				"successful" : self.json_structure["subscribe"]["successful"][0] # "true"
		}

    #~~~~~~~~~~~~~~~~~~~~~~~
	#@json_unwrap
	def Subscribe(self, clientSubscribe, request = None):
		error = None

		GlobalLogger.debug(
			"BayeuxServer: In Subscribe, received: %s" \
			% pprint.pformat(clientSubscribe),debug=True
		)

		clientId = clientSubscribe["clientId"]
		subscription = clientSubscribe["subscription"]
		jsonSub = self.json_structure["subscribe"]
		if clientSubscribe["channel"] != jsonSub["channel"]:
			error = [1201,self.json_errcodes[1201] % (clientSubscribe["channel"])]

		elif not self.cometClients.validate(clientId):
			error = [1105,self.json_errcodes[1105] % (clientId)]

		if not error:
			'''
			Implicit topic publish is allowed. If someone subscribes to a nonexistent 
			topic, create it, if the config allows this to happen. 
			'''

			# Subscription could be a string or a list. If string, make it a list.
			if isinstance(subscription,basestring):
				subscription = [subscription]

			for topic in subscription: # This is an array of at least one topic/channel.
				if not self.cometTopics.subscribeToTopic(topic,clientId):
	
					if not bayeux_config.implicitPublish:
						error = [1205,self.json_errcodes[1205] % (topic)]
						break
	
					elif not self.cometTopics.registerTopics([topic],clientId,implicit=True):
						error = [1204,self.json_errcodes[1204] % (clientSubscribe["channel"])]
						break

					if not self.cometTopics.subscribeToTopic(topic,clientId):
						error = [1206,self.json_errcodes[1206] % (clientSubscribe["channel"])]
						break
		if error:
			return {
					"channel" : self.json_structure["subscribe"]["channel"],
					"clientId" : clientSubscribe["clientId"], # from handshake w/server
					"successful" : self.json_structure["subscribe"]["successful"][1], # "false"
					"error" : error
					}

		clientRef = self.cometClients.alreadyConnected(clientId)
		success = "[" + json_encode({
				"channel" : self.json_structure["subscribe"]["channel"],
				"clientId" : clientSubscribe["clientId"], # from handshake w/server
				"successful" : self.json_structure["subscribe"]["successful"][0] # "true"
		}) + "]"
		#if clientRef:
		#	clientRef.stream.write(success)
		return {
				"channel" : self.json_structure["subscribe"]["channel"],
				"clientId" : clientSubscribe["clientId"], # from handshake w/server
				"successful" : self.json_structure["subscribe"]["successful"][0] # "true"
		}

    #~~~~~~~~~~~~~~~~~~~~~~~
	#@json_unwrap
	def Unsubscribe(self,clientUnsubscribe, request = None):
		error = None

		if clientUnsubscribe["channel"] != self.json_structure["unsubscribe"]["channel"]:
			error = [1201,self.json_errcodes[1201] % (clientUnsubscribe["channel"])]
			
		clientId = clientUnsubscribe["clientId"]
		
		unsubscribe_list = clientUnsubscribe["subscription"]
		if isinstance(unsubscribe_list, basestring):
			unsubscribe_list = [unsubscribe_list]
		
		for topic in unsubscribe_list: 
			if not self.cometTopics.unsubFromTopic(topic,clientId):
				error = [1205,self.json_errcodes[1205] % (topic)]
				break

		if error:
			return {"channel" : self.json_structure["unsubscribe"]["channel"],
					"clientId" : clientId, # from handshake w/server
					"successful" : self.json_structure["unsubscribe"]["successful"][1], # "false"
					"error" : error
					}
		else:
			return {"channel" : self.json_structure["unsubscribe"]["channel"],
					"clientId" : clientId, # from handshake w/server
					"successful" : self.json_structure["unsubscribe"]["successful"][0] # "true"
				}

	#~~~~~~~~~~~~~~~~~~
   	#@json_unwrap
	def Service(self,clientSvcRequest, request = None):
		error = None

		'''
		Make sure they have handshook/connected. 
		'''
		client_id = clientSvcRequest["clientId"]
		if not self.cometClients.validate(client_id):
				error = [1105,self.json_errcodes[1105] % (client_id)]
		
		elif clientSvcRequest["channel"] == self.json_structure["lsService"]["channel"]:
			'''
			This is a sample system service, which executes "ls -l /tmp" and returns STDOUT.
			'''
			p = None
			try:
				p = subprocess.Popen(["ls","-l","/tmp"],stdout=subprocess.PIPE, 
					stderr=subprocess.PIPE, close_fds=True)
			except:
				error = [701,self.json_errcodes[701] % \
					(clientSvcRequest["channel"],formatExceptionInfo())]

			if p:
				stderr = p.stderr.read()
				if stderr and stderr != '':
					error = [701,self.json_errcodes[701] % (clientSvcRequest["channel"],p.stderr)]
				else:
					result = p.stdout.read()

		elif clientSvcRequest["channel"][:12] == self.json_structure["urlService"]["channel"]:

			try:
				'''
				Get component (some_channel) of /service/url/some_channel
				'''
				configChannel = clientSvcRequest["channel"].split('/')[3] # 4th member.

			except (KeyError,IndexError):
				'''
				Unrecognized channel.
				'''
				error = [703,self.json_errcodes[703] % (clientSvcRequest["channel"])]

			if not error:
				try:
					result = urllib2.urlopen(bayeux_config.serviceUrlEndpoints[configChannel]).read()
				except IOError, e:
					if hasattr(e, 'reason'):
						GlobalLogging.error("Service URL failure: %s" % e.reason)
						error = [702,self.json_errcodes[702] % (clientSvcRequest["url"],e.reason)]
					elif hasattr(e, 'code'):
						errorcode = "HTTP error code %d" % e.code
						error = [702,self.json_errcodes[702] % (clientSvcRequest["url"],errorcode)]
						GlobalLogging.error("%d %s" % (error[0],error[1]))
		else:
			'''
			Unrecognized channel.
			'''
			error = [703,self.json_errcodes[703] % (clientSvcRequest["channel"])]
					
		if error:
			return {"channel":clientSvcRequest["channel"],
					"successful":self.json_structure["urlService"]["successful"][1], # "false"
					"error":error
				}
		else:
			return {"channel":clientSvcRequest["channel"],
					"successful":self.json_structure["urlService"]["successful"][0], # "true"
					"response":result
				}

# vim:ts=4:noet:
