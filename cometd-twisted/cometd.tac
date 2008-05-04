## Cometd Configuration Block ##################################################

# verboseLogging: boolean
verboseLogging = True #False

# clientTimeout: integer, minutes
# 	delay before culling inactive clients. Set this lower to reduce memory
# 	footprint on busy servers.
clientTimeout = 10

# connectionTimeout: integer, minutes
#	delay before culling long-running connections that haven't seen any
#	activity.
connectionTimeout = 15

# maxClients: integer
#	maximum number of allowed concurrent clients. Tune this to match
#	available system resources.
maxClients = 20000

# logFile: string, path
#	where should we log cometd activity to?
logFile = "./cometd.log" 

# port: integer
#	where should we log cometd activity to?
port = 8090

# autoCreateChannels: boolean
#	should channels (and user-defined channel names) be limited to those listed
#	in initialChannels?
autoCreateChannels = True

# clientChannels: list
#	A list of channel names, with python regex matching, used to determine if a
#	client-specified channel may be subscribed or published to. Meta topics are
#	not specified here as they are handled before matching against this list
#	takes place
clientChannels = [ r"\/.*" ]

# preAuthKeys: list
#	an array of strings (keys) which clients using them are assumed to be
#	"pre-authenticated" to take administrative actions such as creating
#	channels (if client-creation is turned off) and using extension meta topics 
preAuthKeys = [] # FIXME: implement a capabilities list instead of on/off?

## End Configuration Block #####################################################

# WARNING:  Edit below this line only if you know what you're doing 
from twisted.web2 import http, resource, channel, stream, server, static, http_headers, responsecode
from twisted.python import log
from twisted.internet import reactor
from twisted.application import service, strports
from path import path
import sys; sys.path.append(".")
import cometd


clientTimeoutSec = clientTimeout * 60

cometd.verbose = verboseLogging
cometd.clientTimeout = clientTimeout * 60
cometd.connectionTimeout = connectionTimeout * 60
log.startLogging(path(logFile).abspath().open("a"))

class CometdRunner(resource.Resource):
	# blah, hacky class. Wish we didn't need it
	addSlash = True
	if verboseLogging:
		log.msg("cometd initialized")
	child_examples = static.File(path("../examples").abspath())
	child_cometd = cometd.cometd()

	def render(self, ctx):
		return http.Response(200,
			{ "content-type": http_headers.MimeType('text', 'html') },
			stream=stream.FileStream(path("index.html").abspath().open())
		)


# FIXME: we don't always want to run this!? How do we tell if we're running under twistd?
# FIXME: we should be getting all of this config info from a file!!
site = server.Site(CometdRunner())
application = service.Application("cometd")
s = strports.service('tcp:'+str(port), channel.HTTPFactory(site))
s.setServiceParent(application)

# vim:ts=4:noet:ft=python
