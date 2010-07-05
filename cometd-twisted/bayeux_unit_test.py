import pdb
import unittest
import pprint

from handy import *
setGlobalLogger()

import bayeux
import bayeux_client
import bayeux_server
import cometd_session

class BayeuxTest(unittest.TestCase):
	def setUp(self):
		self.clientStates = cometd_session.CometClients()
		self.clientTopics = cometd_session.CometTopics()
		self.bayeuxClient = bayeux_client.BayeuxClient()
		self.bayeuxServer = bayeux_server.BayeuxServer(self.clientStates,self.clientTopics)

	def tearDown(self):
		del self.bayeuxClient
		del self.bayeuxServer

	def testGoodHandshake(self):
		''' (1) Handshake test from client to server '''
		result = self.bayeuxServer.Handshake(json_decode(self.bayeuxClient.Handshake()))
		pprint.pprint(result)
		print "\n\n"

	def testBadHandshake(self):
		''' (2) Handshake test which fails from client to server: 
			Unravel and modify client response. Re-encode and send to server. 
		'''
		client_handshake_init = json_decode(self.bayeuxClient.Handshake())
		client_handshake_init["channel"] = "/some/garbage"
		result = self.bayeuxServer.Handshake(client_handshake_init)
		print "From testBadHandshake:",result["error"]
		pprint.pprint(result)
		print "\n\n"

	def testPasswdAuthHandshake(self):
		''' (3) Handshake which has to authenticate via htpasswd user name and password 
			(2 requests)
		'''
		client_handshake_init = self.bayeuxClient.Handshake()
		server_response = self.bayeuxServer.Handshake(json_decode(client_handshake_init))
		pprint.pprint(server_response)
		print "\n\n"

		''' Fail the client. Recommend that the client authenticate themselves. 
			Pass it back to the client. 
		'''
		server_response["successful"] = 
			{"successful":self.bayeuxServer.json_structure["handshake"]["successful"][1]} # False
		server_response["error"] = [1103,self.bayeuxServer.json_errcodes[1103]]
		server_response["advice"] = 
			{"authenticate":{"authtype": \
				self.bayeuxServer.json_structure["handshake"]["advice"]["authenticate"]["authtype"]}}

		print "Server sending back demand for login, failing handshake:"
		pprint.pprint(server_response)
		print "\n\n"
		''' Client detects error, responds incorrectly, passes it back to the server. '''
		nserver_response = self.bayeuxServer.Handshake(json_decode( \
			self.bayeuxClient.Handshake(server_response,"gloriajw","5test1234")))

		''' Server detects login error '''
		print "Intentional wrong password from client: server response:",server_response["error"]
		pprint.pprint(nserver_response)
		print "\n\n"

		''' Client responds correctly this time. '''
		server_response = self.bayeuxServer.Handshake(json_decode(self.bayeuxClient.Handshake(None,
				"gloriajw","test1234")))

		print "Correct login from client:"
		pprint.pprint(server_response)
		print "\n\n"

		''' Check out server response for errors. '''
		if server_response.has_key("error"):
			print "Whoops, the client/server http auth failed:",server_response["error"]
		else:
			print "If you're down here, test (3) should be fine."

	def testConnect(self):
		''' (4) Test a connect. '''
		handshake = self.bayeuxServer.Handshake(json_decode(\
			self.bayeuxClient.Handshake(None,"gloriajw","test1234")))
		client_connect = self.bayeuxClient.Connect(\
			handshake["clientId"],handshake["supportedConnectionTypes"][1]) # long_polling
		server_response = self.bayeuxServer.Connect(json_decode(client_connect))
		pprint.pprint(server_response)
		print "\n\n"

	def testService(self):
		''' (5) Test local service request. '''
		handshake = self.bayeuxServer.Handshake(\
			json_decode(self.bayeuxClient.Handshake(None,"gloriajw","test1234")))
		print "Handshake:"
		pprint.pprint(handshake)
		print "\n\n"

		client_connect = self.bayeuxClient.Connect(\
			handshake["clientId"],handshake["supportedConnectionTypes"][1]) # long_polling
		server_response = self.bayeuxServer.Connect(json_decode(client_connect))
		print "Connect:"
		pprint.pprint(client_connect)
		print "\n\n"

		result = self.bayeuxServer.Service(json_decode(\
			self.bayeuxClient.LocalService(handshake["clientId"])))
		print "Local service response:"
		pprint.pprint(result)
		print "\n\n"

		result = self.bayeuxServer.Service(json_decode(\
			self.bayeuxClient.URLService(handshake["clientId"])))
		print "URL service response:"
		pprint.pprint(result)
		print "\n\n"

	def testPublishSubscribe(self):
		''' (6) Test publish and subscribe with per-message guaranteed delivery. '''
		handshake = self.bayeuxServer.Handshake(json_decode(\
			self.bayeuxClient.Handshake(None,"gloriajw","test1234")))
		print "Handshake:"
		pprint.pprint(handshake)
		print "\n\n"

		client_connect = self.bayeuxClient.Connect(\
			handshake["clientId"],handshake["supportedConnectionTypes"][1]) # long_polling
		server_response = self.bayeuxServer.Connect(json_decode(client_connect))
		print "Connect:"
		pprint.pprint(client_connect)
		print "\n\n"
		print "Server response:"
		pprint.pprint(server_response)
		print "\n\n"

		handshake2 = self.bayeuxServer.Handshake(json_decode(\
			self.bayeuxClient.Handshake(None,"gloriajw2","test12345")))
		print "Handshake:"
		pprint.pprint(handshake2)
		print "\n\n"

		client2_connect = self.bayeuxClient.Connect(\
			handshake2["clientId"],handshake2["supportedConnectionTypes"][1]) # long_polling
		server_response = self.bayeuxServer.Connect(json_decode(client2_connect))
		print "Connect:"
		pprint.pprint(client2_connect)
		print "\n\n"
		print "Server response:"
		pprint.pprint(server_response)
		print "\n\n"

		id=1

		client_publish = self.bayeuxClient.Publish(\
			handshake["clientId"],"/twelfth/night","No meaningful data for the moment.",id,True)
		server_response = self.bayeuxServer.Publish(json_decode(client_publish))
		print "Publish:"
		pprint.pprint(client_publish)
		print "\n\n"
		print "Server response:"
		pprint.pprint(server_response)
		print "\n\n"

		client2_subscribe = self.bayeuxClient.Subscribe(\
			handshake2["clientId"],["/twelfth/night"],True)
		server_response = self.bayeuxServer.Subscribe(json_decode(client2_subscribe))
		print "Subscribe:"
		pprint.pprint(client2_subscribe)
		print "\n\n"
		print "Server response:"
		pprint.pprint(server_response)
		print "\n\n"
			
		f=open("../tests/twelfth_night","r")
		act1scene1 = f.read()
		f.close()

		client2_deliver = self.bayeuxClient.Deliver(\
			handshake2["clientId"],"/twelfth/night",act1scene1[0:2000],id,True)

		'''
		There should be 2 outbound messages, one for the sender, as a guaranteed delivery
		ACK, the second to the subscriber.
		'''
		outbound_messages = self.bayeuxServer.PrepareToDeliver(json_decode(client2_deliver))

		print "All Outbound messages:"
		pprint.pprint(outbound_messages)

		client_deliver = json_decode(self.bayeuxClient.Deliver(\
			handshake["clientId"],"/twelfth/night",act1scene1[2001:5000],id,True))
		[outbound_messages.append(x) for x in self.bayeuxServer.PrepareToDeliver(client_deliver)]

		client_deliver = json_decode(self.bayeuxClient.Deliver(\
			handshake["clientId"],"/twelfth/night",act1scene1[5001:],id,True))
		[outbound_messages.append(x) for x in self.bayeuxServer.PrepareToDeliver(client_deliver)]

		print "All Outbound messages:"
		pprint.pprint(outbound_messages)

def suite():
    return unittest.makeSuite(BayeuxTest)

if __name__ == '__main__':
    # When this module is executed from the command-line, run all its tests
    x=suite()
    unittest.TextTestRunner().run(x)

# vim:ts=4:noet:
