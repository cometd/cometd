#!/bin/bash

# TODO: Make this script test for existing modules before automatically setting them up

echo "setting up"
cd /tmp
mkdir cometd_deps
cd cometd_deps

echo "installing POE"
wget "http://search.cpan.org/CPAN/authors/id/R/RC/RCAPUTO/POE-0.9500.tar.gz"
tar -zxvf POE-0.9500.tar.gz
cd POE-0.9500
perl Makefile.PL --default
make
make install
make clean
cd ..

echo "installing BSD::Resource"
wget "http://search.cpan.org/CPAN/authors/id/J/JH/JHI/BSD-Resource-1.28.tar.gz"
tar -zxvf BSD-Resource-1.28.tar.gz
cd BSD-Resource-1.28
perl Makefile.PL
make
make install
make clean
cd ..

echo "installing Class::Accessor"
wget "http://search.cpan.org/CPAN/authors/id/K/KA/KASEI/Class-Accessor-0.30.tar.gz"
tar -zxvf Class-Accessor-0.30.tar.gz
cd Class-Accessor-0.30
perl Makefile.PL
make
make install
make clean
cd ..

echo "installing JSON"
wget "http://search.cpan.org/CPAN/authors/id/M/MA/MAKAMAKA/JSON-1.07.tar.gz"
tar -zxvf JSON-1.07.tar.gz
cd JSON-1.07
perl MakeFile.PL
make
make install
make clean
cd ..

echo "installing POE::Filter::JSON"
wget "http://search.cpan.org/CPAN/authors/id/X/XA/XANTUS/POE-Filter-JSON-0.01.tar.gz"
tar -zxvf POE-Filter-JSON-0.01.tar.gz
cd POE-Filter-JSON-0.01
perl Makefile.PL
make
make install
make clean
cd ..

echo "installing HTTP::Status"
wget "http://search.cpan.org/CPAN/authors/id/G/GA/GAAS/libwww-perl-5.805.tar.gz"
tar -zxvf libwww-perl-5.805.tar.gz
cd libwww-perl-5.805
perl Makefile.PL -n
make
make install
make clean
cd ..

echo "installing URI"
wget "http://search.cpan.org/CPAN/authors/id/G/GA/GAAS/URI-1.35.tar.gz"
tar -zxvf URI-1.35.tar.gz
cd URI-1.35
perl Makefile.PL -n
make
make install
make clean
cd ..

# ----------------------------------------------------------------------------
#                httpd.pl requirements below this line
# ----------------------------------------------------------------------------
echo "installing URI"
wget "http://search.cpan.org/CPAN/authors/id/G/GR/GREGFAST/POE-Component-Server-HTTPServer-0.9.2.tar.gz"
tar -zxvf POE-Component-Server-HTTPServer-0.9.2.tar.gz
cd POE-Component-Server-HTTPServer-0.9.2
perl Makefile.PL -n
make
make install
make clean
cd ..

echo "installing Sub::Exporter"
wget "http://search.cpan.org/CPAN/authors/id/R/RJ/RJBS/Sub-Exporter-0.972.tar.gz"
tar -zxvf Sub-Exporter-0.972.tar.gz
cd Sub-Exporter-0.972
perl Makefile.PL
make
make install
make clean

echo "installing Sub::Install"
wget "http://search.cpan.org/CPAN/authors/id/R/RJ/RJBS/Sub-Install-0.924.tar.gz"
tar -zxvf Sub-Install-0.924.tar.gz
cd Sub-Install-0.924
perl Makefile.PL
make
make install
make clean

echo "installing Data::UUID"
wget "http://search.cpan.org/CPAN/authors/id/R/RJ/RJBS/Data-UUID-0.148.tar.gz"
tar -zxvf Data-UUID-0.148.tar.gz
cd Data-UUID-0.148
perl Makefile.PL
make
make install
make clean

echo "installing Data::GUID"
wget "http://search.cpan.org/CPAN/authors/id/R/RJ/RJBS/Data-GUID-0.042.tar.gz"
tar -zxvf Data-GUID-0.042.tar.gz
cd Data-GUID-0.042
perl Makefile.PL -n
make
make install
make clean
cd ..

echo "installing Data::OptList"
wget "http://search.cpan.org/CPAN/authors/id/R/RJ/RJBS/Data-OptList-0.101.tar.gz"
tar -zxvf Data-OptList-0.101.tar.gz
cd Data-OptList-0.101
perl Makefile.PL
make
make install
make clean
cd ..

echo "installing Params::Util"
wget "http://search.cpan.org/CPAN/authors/id/A/AD/ADAMK/Params-Util-0.22.tar.gz"
tar -zxvf Params-Util-0.22.tar.gz
cd Params-Util-0.22
perl Makefile.PL
make
make install
make clean
cd ..

echo "installing Mime::Types"
wget "http://search.cpan.org/CPAN/authors/id/M/MA/MARKOV/MIME-Types-1.18.tar.gz"
tar -zxvf MIME-Types-1.18.tar.gz
cd MIME-Types-1.18
perl Makefile.PL
make
make install
make clean
cd ..