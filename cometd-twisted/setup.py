from distutils.core import setup

setup(
  name               = 'cometd-twisted',
  version            = '0.1',
  py_modules         = ['cometd'],
  scripts            = ['comet-client.py'],
  data_files         = [ ('/etc/cometd', ['cometd.tac']) ],
  author             = 'Alex Russell',
  author_email       = 'alex@dojotoolkit.org',
  maintainer         = 'Martin A. Brown',
  maintainer_email   = 'mabrown@sitepen.com',
  url                = 'http://cometd.com/',
  download_url       = 'http://svn.xantus.org/shortbus/trunk/cometd-twisted/',
  description        = "an HTTP-based event routing bus",
  long_description   = """
Cometd is a scalable HTTP-based event routing bus that uses a push
technology pattern known as Comet. The term 'Comet' was coined by
Alex Russell in his post 'Comet: Low Latency Data for the
Browser'. The letter d is for 'Daemon', as described by
Answers.com, Daemon is a program or process that sits idly in the
background until it is invoked to perform its task. Cometd
consists of a protocol spec called Bayeux, javacript libraries
(dojo toolkit), and an event server.
                             """
     )

