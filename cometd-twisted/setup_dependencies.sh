#!/bin/bash

echo "settting up"
cd /tmp
mkdir cometd_deps
cd cometd_deps

echo "installing path"
wget http://www.jorendorff.com/articles/python/path/path-2.1.zip
unzip path-2.1.zip
cd path-2.1
sudo python setup.py install
cd ..

echo "installing lxml"
wget http://codespeak.net/lxml/lxml-1.1.1.tgz
tar -zxvf lxml-1.1.1.tgz
cd lxml-1.1.1
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
