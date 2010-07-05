#!/bin/bash

echo "settting up"
cd /tmp
mkdir cometd_deps
cd cometd_deps

# This is dangerous if the Python user already has a different version of setuptools.
# Plus, it's not used below at all. 
#echo "installing setuptools"
#wget "http://pypi.python.org/packages/source/s/setuptools/setuptools-0.6c8.tar.gz"
#tar -zxvf setuptools-0.6c8.tar.gz
#cd setuptools-0.6c8.tar.gz
#sudo python setup.py install
#cd ..

echo "installing pytz"
wget "http://pypi.python.org/packages/source/p/pytz/pytz-2008a.tar.gz"
tar xvzf pytz-2008a.tar.gz
cd pytz-2008a
sudo python setup.py install
cd ..

echo "installing cjson"
wget "http://pypi.python.org/packages/source/p/python-cjson/python-cjson-1.0.5.tar.gz#md5=4d55b66ecdf0300313af9d030d9644a3"
tar -zxvf python-json-1.0.5.tar.gz
cd python-json-1.0.5
sudo python setup.py install
cd ..

echo "installing simplejson"
wget "http://pypi.python.org/packages/source/s/simplejson/simplejson-1.7.tar.gz"
tar -zxvf simplejson-1.7.tar.gz
cd simplejson-1.7
sudo python setup.py install
cd ..

echo "installing path"
wget http://cheeseshop.python.org/packages/source/p/path.py/path-2.2.zip
unzip path-2.2.zip
cd path-2.2
sudo python setup.py install
cd ..

echo "installing lxml"
wget http://codespeak.net/lxml/lxml-2.0.1.tgz
tar -zxvf lxml-2.0.1.tgz
cd lxml-2.0.1
sudo python setup.py install
cd ..

echo "installing Twisted"
wget http://tmrc.mit.edu/mirror/twisted/Twisted/2.4/Twisted-2.4.0.tar.bz2
tar -jxvf Twisted-2.4.0.tar.bz2
cd Twisted-2.4.0/ZopeInterface-3.1.0c1/
sudo python setup.py install
cd ..
sudo python setup.py install
cd ..

echo "installing TwistedWeb2"
wget http://tmrc.mit.edu/mirror/twisted/Web2/TwistedWeb2-0.2.0.tar.bz2
tar -jxvf TwistedWeb2-0.2.0.tar.bz2
cd TwistedWeb2-0.2.0/
sudo python setup.py install
cd ..

cd ..
echo "cleaning up"
rm -rf cometd_deps

echo "done"
