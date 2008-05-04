#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
# Bayeux protocol supplemental functions.
# Note that for test and learning purposes, there are both client and 
# server implementations present.
#
# GloriaJW. 2008_01_04
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
import sys,traceback,string
from sets import Set
import random,re
from crypt import crypt
import pdb

from handy import *
import bayeux_config

#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
# Global variables
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
global BAYEUX_STRUCTURE
global BAYEUX_ERRCODES
BAYEUX_STRUCTURE = None
BAYEUX_ERRCODES = None

#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
# Decorators
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
def json_wrap(func):
	def wrapper(*arg):
		return json_encode(func(*arg))
	return wrapper

def json_unwrap(func):
	def wrapper(*arg):
		'''
		This only works when wrapped around class methods. 
		It assumes the first parameter is always "self", and ignores it.
		'''
		try:
			return func(arg[0],json_decode(arg[1]))
		except:
			try:
				return func(json_decode(arg[0]))
			except:
				#pdb.set_trace()
				return func(*arg) # not json encoded.

	return wrapper
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
# Global functions
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
#~~~~ jsonp ~~~~~~~~~
def jsonp_encode(json_string,jsonp_callback,connection_types):
	""" The json_string has to already be json encoded. 
		Use the above decorator if necessary. """
	return jsonp_callback + "(" + connection_types["envelope"] % (json_string) + ")"

def jsonp_decode(jsonp_string):
	jf = re.search('^\s*(\w+)\((.*)\)\s*$',jsonp_string)
	if not jf:
		return None
	jsonp_function = jf.group(1)
	json_string = jf.group(2)
	return (jsonp_function,json_string) # returns a tuple

#~~~~ auth ~~~~~~~~~
'''
NOTE: This only checks crypt() password. 
Apache has a proprietary MD5 algorithm, written in C.
So for now we're only working with crypt() passwords.
See: http://httpd.apache.org/docs/2.2/misc/password_encryptions.html
'''
def htpasswdAuthenticate(username,password,filename=bayeux_config.htpasswdFile):
	lines = [l.rstrip().split(':', 1) for l in file(filename).readlines()]
	lines = [l for l in lines if l[0] == username]
	if not lines:
		return None
	hashedPassword = lines[0][1]
	return hashedPassword == crypt(password, hashedPassword[:2])

#~~~~ main protocol unravel ~~~~~~~~~
def import_bayeux_structures(): 
	global BAYEUX_STRUCTURE
	global BAYEUX_ERRCODES
	if BAYEUX_STRUCTURE: 
		return BAYEUX_STRUCTURE,BAYEUX_ERRCODES

	f = open("../bayeux/bayeux.json",'r')
	json_dict = re.sub('\/\*.*\*\/','',f.read())
	json_dict = re.sub(' *\n','',json_dict)
	json_dict = re.sub('^ *','',json_dict)
	f.close()
	#print json_dict
	BAYEUX_STRUCTURE = json_decode(json_dict)

	f = open("../bayeux/bayeux_error_codes.json",'r')
	json_dict = re.sub('\/\*.*\*\/','',f.read())
	json_dict = re.sub(' *\n','',json_dict)
	json_dict = re.sub('^ *','',json_dict)
	f.close()
	#print json_dict
	BAYEUX_ERRCODES = json_decode(json_dict)

	'''
	Convert keys to integer values.
	'''
	for key in BAYEUX_ERRCODES.keys():
		ikey = int(key)
		BAYEUX_ERRCODES[ikey] = BAYEUX_ERRCODES[key]

	return BAYEUX_STRUCTURE,BAYEUX_ERRCODES

'''
Returns 0,1,2 :  0 is equal, 1 is client < server, 2 is client > server.
'''
def check_version(client_version):
	cversion = client_version.split('.')
	sversion = bayeux_config.version.split('.')
	for i in range(0,len(cversion)):
		'''
		Check one digit at a time.
		'''
		for j in range(0,len(cversion)):
			try:
				if int(cversion[i][j]) > int(sversion[i][j]):
					return 2
			except IndexError: 
				if len(cversion) > len(sversion):
					return 2
				return 1
			try:
				if int(cversion[i][j]) < int(sversion[i][j]):
					return 1
			except IndexError: # If client version id is longer, assume it is greater for now.
				if len(cversion) > len(sversion):
					return 2
				return 1
	return 0

def generate_random_client_id():
	alphanum = string.letters + string.digits

	# range goes to len-1 automatically, so this is OK.
	# I prefer a random key length, min 10 chars.
	keylen = random.randint(10,len(alphanum))

	# += string operator is slow, this is preferred.
	return ''.join([random.choice(alphanum) for k in xrange(keylen)])

if __name__ == "__main__":
	print import_bayeux_structures()

# vim:ts=4:noet:
