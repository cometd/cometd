## Bayeux Configuration Block ##################################################

# These timezone strings are standard Olson values, supported in the OS. 
# See /usr/share/zoneinfo on Linux based systems for more info.

serverTimeZone = "US/Eastern"
#serverTimeZone = "US/Pacific"

version = "1.01"

# If this is True, a subscribe causes an implicit publish.
# With an implicit publish, any subscriber can delete the topic/channel,
# since there is not explicit owner or topic creation. You have been forewarned!
implicitPublish=True

# Choices are None, "htpasswd", "OpenId", "Auto" (use all available choices)
loginRequired = None

htpasswdFile= "./.htpasswd"

# This is in milliseconds
handshakeTimeout = 10000 

# This makes URLs available as service URL endpoints
serviceUrlEndpoints={
		"bbc":"http://news.bbc.co.uk/",
		"ny_times":"http://www.nytimes.com/",	
		"cartoon_network":"http://www.cartoonnetwork.com/"
	}

# When we assume that a long polling client has really lost a connection. 
# Inactivity, in minutes.
longPollingTimeout = 5

# General connection cleanup, idle processing, etc. 
# How often to run these tasks, in minutes.
cleanupTimer = 2

# Testing mode looks elsewhere in the HTTP header for data (to circumvent 
# a Twisted bug), and may even change the stream allocation in the future.
# This must be on for the bayeux_test_harness code to work.
testingMode = True
