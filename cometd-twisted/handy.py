#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
# Handy generic functions.
#
# GloriaJW. 2008_01_20
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
import os,sys,traceback,datetime,pytz
import logging # just in case we need it.
import pdb

#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
# Global variables
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
global GlobalLogger

try:
	import cjson # much faster than simplejson

	def json_encode(some_struct):
		return cjson.encode(some_struct)

	def json_decode(some_struct):
		return cjson.decode(some_struct)


except:
	import simplejson
	def json_encode(some_struct):
		return simplejson.dumps(some_struct)

	def json_decode(some_struct):
		return simplejson.loads(some_struct)

#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
# Global functions
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
#~~~~ generic logging ~~~~~~~~~~~
def setGlobalLogger(logging_module=None):
	global GlobalLogger
	if not logging_module:
		try:
			os.makedirs('./logs')
		except:
			pass

		#logging.basicConfig(level=logging.DEBUG,
		logging.basicConfig(level=logging.ERROR,
			format='%(asctime)s %(levelname)s %(message)s',
			filename='./logs/comet-bayeux.log',
			filemode='a')

		GlobalLogger = logging
	else:
		GlobalLogger = logging_module
		GlobalLogger.info = GlobalLogger.msg
		GlobalLogger.warn = GlobalLogger.msg
		GlobalLogger.debug = GlobalLogger.msg

#~~~~ generic error handling ~~~~~~~
def formatExceptionInfo(maxTBlevel=5):
	cla, exc, trbk = sys.exc_info()
	excName = cla.__name__
	try:
		excArgs = ' '.join(exc.__dict__["args"])
	except KeyError:
		excArgs = "<no args>"
		excTb = ' '.join(traceback.format_tb(trbk, maxTBlevel))
		return excName + ":" + excArgs + ":" + excTb

#~~~~ now() in UTC 0 ~~~~~~~
def rightNowInUTC(serverTimeZone):
	rightnow = datetime.datetime.now()
	rightnow = pytz.timezone(serverTimeZone).localize(rightnow)
	rightnow = rightnow.astimezone(pytz.timezone('UTC'))
	return rightnow

# vim:ts=4:noet:
