#
# This needs to be redesigned.
#
# auto-generated configuration
import md5,time

tmp = md5.new()
tmp.update(str(time.ctime()))
mimeBoundary = tmp.hexdigest()
errorHttpCode = 406

ConnectionTypes = {
	"iframe": {
		"closeOnDelivery": False,
		"preamble":		"""
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN"
	"http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">

<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en">
	<head>
		<meta http-equiv="Content-Type" content="text/html; charset=utf-8"></meta>
		<title>cometd: The Long Tail of Comet</title>
	</head>
	<body onload="window.parent.dojox.cometd.tunnelCollapse();">
		""",
		"envelope":		"""
			<br />
			<script type="text/javascript">
				window.parent.dojox.cometd.deliver(%s);
			</script>
			<br />
		""" + (" " * 2048), # double this if it's not working
		"keepalive":	"<br>" + (" " * 2048),
		"signoff":		"""
		<script type="text/javascript">
			window.parent.dojox.cometd.disconnect();
		</script>
	</body>
</html>
		""",
		# this tunnelInit is borrowed from Dojo
		"tunnelInit":	"""
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN"
	"http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">

<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en">
	<head>
		<title>cometd: The Long Tail of Comet</title>
		<meta http-equiv="Content-Type" content="text/html; charset=utf-8"></meta>
		<script type="text/javascript">
			// window.parent.console.debug("tunnelInit");
			var noInit = false;
			var domain = "";
			function init(){
				var sparams = document.location.search;
				if(sparams.length >= 0){
					if(sparams.charAt(0) == "?"){
						sparams = sparams.substring(1);
					}
					var ss = (sparams.indexOf("&amp;") >= 0) ? "&amp;" : "&";
					sparams = sparams.split(ss);
					for(var x=0; x<sparams.length; x++){
						var tp = sparams[x].split("=");
						if(typeof window[tp[0]] != "undefined"){
							window[tp[0]] = ((tp[1]=="true")||(tp[1]=="false")) ? eval(tp[1]) : tp[1];
						}
					}
				}

				if(noInit){ return; }
				/*
				if(domain.length > 0){
					document.domain = domain;
				}
				*/
				if(window.parent != window){
					//Notify parent that we are loaded.
					window.parent.dojox.cometd.tunnelInit(window.location, document.domain);
				}
			}
		</script>
	</head>
	<body onload="try{ init(); }catch(e){ alert(e); }">
		<h4>cometd: The Long Tail of Comet</h4>
	</body>
</html>""",
		"contentType": "text/html"
	},

	"callback-polling": {
		# NOTE: the "callback-polling" method can be used via ScriptSrcIO for
		# x-domain polling
		"closeOnDelivery": True,
		"preamble":		"",
		# "envelope":		"cometd.deliver(%s);",
		"envelope":		"(%s)",
		"keepalive":	" ",
		"signoff":		"",
		"tunnelInit":	"",
		"contentType": "text/javascript"
	},

	"long-polling": {
		"closeOnDelivery": True,
		"preamble":		"",
		"envelope":		"%s",
		"keepalive":	"",
		"signoff":		"",
		"tunnelInit":	"",
		"contentType": "text/plain"
	},

	"ie-message-block": {
		"closeOnDelivery": False,
		"preamble":		"""<?xml version="1.0" encoding="UTF-8"?>
			<cometd>
		""",
		"envelope":		"<message><![CDATA[%s]]></message>",
		"keepalive":	"<message><![CDATA[]]></message>",
		"signoff":		"""
			</cometd>
		""",
		"tunnelInit":	"",
		"contentType": "text/xml"
	},

	"mime-message-block": {
		"closeOnDelivery": False,
		"preamble":		"--"+mimeBoundary+"\r\n",
		"envelope":		"""Content-Type: text/plain\r\n\r\n
%s
\r\n
--"""+mimeBoundary+"\r\n",
		"signoff":		"\n--"+mimeBoundary+"--\n",
		"tunnelInit":	"",
		"contentType": "multipart/x-mixed-replace; boundary=%s" % (mimeBoundary,)
	},
	# failed experiment
	#		"preamble":		"",
	#		"envelope":		"\n--"+mimeBoundary+"""
	#Content-Type: text/plain\n\n
	#%s
	#
	#""",
	# --"""+mimeBoundary+"\n",

#	"text-stream": {
#		"closeOnDelivery": False,
#		"preamble":		"--"+mimeBoundary+"\r\n",
#		"envelope":		"""\r\n%s\r\n--"""+mimeBoundary+"\r\n"+(1024*" "),
#		"signoff":		"\r\n--"+mimeBoundary+"--\r\n",
#		"tunnelInit":	"",
#		"contentType": "text/plain"
#	},

#	"flash": {
#		"closeOnDelivery": False,
#		"preamble":		"",
#		"envelope":		"",
#		"signoff":		"",
#		"tunnelInit":	"",
#		"contentType": "text/xml"
#	}
}

# need to specify http-polling for entirely disconnected clients!

SupportedConnectionTypes = [
	"callback-polling", "long-polling", 
	 "mime-message-block", "iframe", 
	 # "ie-message-block", # doesn't really work?
]
