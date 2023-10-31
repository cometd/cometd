#!/usr/bin/env bash

yes_no() {
    while true; do
        echo -n $1 ""
        read yn
        case ${yn:-$2} in
            [Yy]* ) return 0;;
            [Nn]* ) return 1;;
            * ) echo "Please answer yes or no.";;
        esac
    done
}

echo "Running release script with arguments" "$@"

COMETD_DIR=$1
VERSION=$2

echo "Uploading distribution"
dd status=progress if=${COMETD_DIR}/cometd-distribution/target/cometd-${VERSION}-distribution.tar.gz | ssh ubuntu@download.cometd.org "sudo -u www-data dd of=/var/www/download.cometd.org/cometd-${VERSION}-distribution.tar.gz"

echo "Uploading javadocs"
cd ${COMETD_DIR}/cometd-java
mvn javadoc:aggregate-jar -Dspotless.check.skip=true
DOCS_ROOT="/var/www/docs.cometd.org"
DOCS_DIR="${DOCS_ROOT}/${VERSION}"
ssh ubuntu@docs.cometd.org "sudo -u www-data mkdir -p ${DOCS_DIR}/apidocs"
dd status=progress if=${COMETD_DIR}/cometd-java/target/cometd-java-${VERSION}-javadoc.jar | ssh ubuntu@docs.cometd.org "sudo -u www-data dd of=${DOCS_DIR}/cometd-java-${VERSION}-javadoc.jar"
ssh ubuntu@docs.cometd.org "sudo -u www-data unzip ${DOCS_DIR}/cometd-java-${VERSION}-javadoc.jar -d ${DOCS_DIR}/apidocs"

echo "Uploading reference book"
tar cvf - -C ${COMETD_DIR}/cometd-documentation/target/html . | ssh ubuntu@docs.cometd.org "sudo -u www-data tar -C ${DOCS_DIR} -xf -"

if yes_no "Relink documentation ? (Y/n)" y; then
  echo "Relinking documentation"
  ssh ubuntu@docs.cometd.org "sudo -u www-data bash -c 'cd ${DOCS_ROOT} && ln -fns ${VERSION} current7'"
fi

echo "Updating cometd-javascript repository"
COMETD_JS_DIR=${COMETD_DIR}/target/release/cometd-javascript
git clone --branch 7.0.x git@github.com:cometd/cometd-javascript.git ${COMETD_JS_DIR}

COMETD_JS_SOURCE=${COMETD_DIR}/cometd-javascript/common/target/cometd-javascript-common-${VERSION}/js
cp -v ${COMETD_JS_SOURCE}/cometd/cometd.js ${COMETD_JS_DIR}
cp -v ${COMETD_JS_SOURCE}/cometd/*Extension.js ${COMETD_JS_DIR}
cp -v ${COMETD_JS_SOURCE}/cometd/cometd.d.ts ${COMETD_JS_DIR}

cd ${COMETD_JS_DIR}

cat <<EOF > ${COMETD_JS_DIR}/package.json
{
  "name": "cometd",
  "version": "${VERSION}",
  "description": "Comet and WebSocket library for web messaging",
  "keywords": ["comet", "websocket", "messaging", "pubsub", "publish", "subscribe", "rpc", "p2p", "peer-to-peer"],
  "homepage": "https://cometd.org",
  "bugs": {
    "url": "https://bugs.cometd.org"
  },
  "license": "Apache-2.0",
  "main": "cometd.js",
  "types": "cometd.d.ts",
  "repository": {
    "type": "git",
    "url": "https://github.com/cometd/cometd.git"
  }
}
EOF

git add .
git commit -m "Release ${VERSION}."
git tag -am "Release ${VERSION}." ${VERSION}
git push --follow-tags

if yes_no "Publish to NPM ? (Y/n)" y; then
  npm publish
fi
