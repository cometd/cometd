## Cometd Configuration Block ##################################################

# verboseLogging: boolean
verboseLogging = False

# clientTimeout: integer, minutes
# 	delay before culling inactive clients. Set this lower to reduce memory
# 	footprint on busy servers.
clientTimeout = 10

# connectionTimeout: integer, minutes
#	delay before culling long-running connections that haven't seen any
#	activity.
connectionTimeout = 15

# logFile: string, path
#	where should we log cometd activity to?
logFile = "/var/log/cometd" 

# port: integer
#	where should we log cometd activity to?
port = 8080

## End Configuration Block #####################################################

# WARNING:  Edit below this line only if you know what you're doing 
from twisted.web2 import http, resource, channel, stream, server, static, http_headers, responsecode
from twisted.python import log
from twisted.internet import reactor
from twisted.application import service, strports
from path import path
import sys; sys.path.append(".")
import cometd


cometd.verbose = verboseLogging
cometd.clientTimeout = clientTimeout * 60
cometd.connectionTimeout = connectionTimeout * 60
log.startLogging(path(logFile).abspath().open("a"))

class CometdRunner(resource.Resource):
	# blah, hacky class. Wish we didn't need it
	addSlash = True
	if verboseLogging:
		log.msg("cometd initialized")
	child_tests = static.File(path("./tests").abspath())
	child_dojo = static.File(path("./dojo").abspath())
	child_cometd = cometd.cometd()

	def render(self, ctx):
		return http.Response(200,
			{ "content-type": http_headers.MimeType('text', 'plain') },
			stream=stream.FileStream(path("cometd.txt").abspath().open())
		)


# FIXME: we don't always want to run this!? How do we tell if we're running under twistd?
# FIXME: we should be getting all of this config info from a file!!
site = server.Site(CometdRunner())
application = service.Application("cometd")
s = strports.service('tcp:'+str(port), channel.HTTPFactory(site))
s.setServiceParent(application)

# vim:ts=4:noet:ft=python
