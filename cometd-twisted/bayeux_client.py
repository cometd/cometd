#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
# Bayeux client protocol interface.
# Note that for test and learning purposes, there are both client and 
# server implementations present.
#
# GloriaJW. 2008_01_04
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
from sets import Set
import pdb

from handy import *
from bayeux import *
import bayeux_config

#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
# Class definitions
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
class BayeuxClient:
	def __init__(self):
		self.json_structure,self.json_errcodes = import_bayeux_structures()

	#~~~~~~~~~~~~~~~~~~~~~~~
	@json_wrap
	def Handshake(self,server_response=None,username=None,password=None):
		if server_response:
			if server_response.has_key("error"):
				GlobalLogger.debug("BayeuxClient: Received response from server: %s" % \
					server_response["error"])
				if server_response["error"][0] in (1102,1103):
					""" Server wants a login/passwd, or previous login attempt failed. """
					#conntype_set = Set(server_response["supportedConnectionTypes"])
					# This is arbitrary.
					authtype_set = Set(["htpasswd","OpenId"])

					if "htpasswd" in authtype_set:
						return {"channel" : self.json_structure["handshake"]["channel"],
								"version" : "1.0",
								"minimumVersion" : "1.0",
								"supportedConnectionTypes" : \
								self.json_structure["handshake"]["supportedConnectionTypes"][2:4],
								"ext": { "authenticate":{"authtype":"htpasswd",
															"user":username,
															"password":password
														}
										}
								}
					elif "OpenID" in authtype_set:
						return {"channel" : self.json_structure["handshake"]["channel"],
								"version" : "1.0",
								"minimumVersion" : "1.0",
								"supportedConnectionTypes" : \
								self.json_structure["handshake"]["supportedConnectionTypes"][2:4],
								"ext": { "authenticate":{"authtype":"OpenId",
															"user":username,
															"password":password
														}
										}
								}

				else:
					return None # unrecognized error
					
		elif username and password:

			# This is arbitrary.
			authtype_set = Set(["htpasswd","OpenId"])

			if "htpasswd" in authtype_set:
				return {"channel" : self.json_structure["handshake"]["channel"],
						"version" : "1.0",
						"minimumVersion" : "1.0",
						"supportedConnectionTypes" : \
							self.json_structure["handshake"]["supportedConnectionTypes"][2:4],
						"ext": { "authenticate":{"authtype":"htpasswd",
													"user":username,
													"password":password
												}
								}
						}
			elif "OpenID" in authtype_set:
				return {"channel" : self.json_structure["handshake"]["channel"],
						"version" : "1.0",
						"minimumVersion" : "1.0",
						"supportedConnectionTypes" : \
							self.json_structure["handshake"]["supportedConnectionTypes"][2:4],
						"ext": { "authenticate":{"authtype":"OpenId",
													"user":username,
													"password":password
												}
								}
						}
		else:
			return {"channel" : self.json_structure["handshake"]["channel"],
					"version" : "1.0",
					"minimumVersion" : "1.0",
					"supportedConnectionTypes" : \
						self.json_structure["handshake"]["supportedConnectionTypes"][2:4]
					}

	#~~~~~~~~~~~~~~~~~~~~~~~
	@json_wrap
	def Connect(self,clientId,connection_type,server_response=None):

		if server_response:
			if server_response.has_key("error"):
				GlobalLogger.debug("BayeuxClient: Received response from server: %s" % \
					server_response["error"])

		return {"channel" : self.json_structure["connect"]["channel"],
				"clientId" : clientId, # given to us via a successful handshake w/server
				"connectionType" : connection_type
				}
			
	#~~~~~~~~~~~~~~~~~~~~~~~
	@json_wrap
	def Publish(self,clientId,channel,data,id=None,guaranteedDelivery=True,server_response=None):

		if server_response:
			if server_response.has_key("error"):
				GlobalLogger.debug("BayeuxClient: Received response from server: %s" % \
					server_response["error"])

		return {"channel" : channel,
				"clientId" : clientId, # given to us via a successful handshake w/server
				"ext" :{"guaranteedDelivery":\
					self.json_structure["publish"]["ext"]["guaranteedDelivery"][0]} # true
				}
			
	#~~~~~~~~~~~~~~~~~~~~~~~
	@json_wrap
	def Subscribe(self,clientId,channels,guaranteedDelivery=False,server_response=None):

		if server_response:
			if server_response.has_key("error"):
				GlobalLogger.debug("BayeuxClient: Received response from server: %s" % \
					server_response["error"])

		return {"channel" : self.json_structure["subscribe"]["channel"],
				"clientId" : clientId, # given to us via a successful handshake w/server
				"subscription": channels, # channels is an array
				}
			
	#~~~~~~~~~~~~~~~~~~~~~~~
	@json_wrap
	def Deliver(self,clientId,channel,data,id,guaranteedDelivery=True,server_response=None):

		if server_response:
			if server_response.has_key("error"):
				GlobalLogger.debug("BayeuxClient: Received response from server: %s" % \
					server_response["error"])

		return {"channel" : channel,
				"clientId" : clientId, # given to us via a successful handshake w/server
				"data": data,
				"id": id,
           		"ext":{"guaranteedDelivery":\
					self.json_structure["deliver"]["ext"]["guaranteedDelivery"][0]} # true
				}
			
	#~~~~~~~~~~~~~~~~~~~~~~~
	@json_wrap
	def LocalService(self,clientId,server_response=None):
		if server_response:
			if server_response.has_key("error"):
				GlobalLogger.debug("BayeuxClient: Received response from server: %s" % \
					server_response["error"])

		return {"channel" : self.json_structure["lsService"]["channel"],
				"clientId" : clientId # given to us via a successful handshake w/server
				}
			
	#~~~~~~~~~~~~~~~~~~~~~~~
	@json_wrap
	def URLService(self,clientId,server_response=None):
		if server_response:
			if server_response.has_key("error"):
				GlobalLogger.debug("BayeuxClient: Received response from server: %s" % \
					server_response["error"])

		'''
		"bbc" must be configured in bayeux_config.py, on the server, 
		in serviceUrlEndpoints{}, to work. 
		'''
		return {"channel" : self.json_structure["urlService"]["channel"] + '/' + "bbc",
				"clientId" : clientId, # given to us via a successful handshake w/server
				}
			
# vim:ts=4:noet:
