#!/usr/bin/env bash

yes_no() {
    while true; do
        read -p "$1 " yn
        case ${yn:-$2} in
            [Yy]* ) return 0;;
            [Nn]* ) return 1;;
            * ) echo "Please answer yes or no.";;
        esac
    done
}

set -x

COMETD_DIR=$1
COMETD_JS_DIR=${COMETD_DIR}/target/release/cometd-javascript
VERSION=$2

if yes_no "Update JavaScript Resources to NPM/Bower repository ? (Y/n)" y; then
    git clone git@github.com:cometd/cometd-javascript.git ${COMETD_JS_DIR}

    COMETD_JS_SOURCE=${COMETD_DIR}/cometd-javascript/common/target/cometd-javascript-common-${VERSION}
    cp ${COMETD_JS_SOURCE}/org/cometd.js ${COMETD_JS_DIR}
    cp ${COMETD_JS_SOURCE}/org/cometd/AckExtension.js ${COMETD_JS_DIR}
    cp ${COMETD_JS_SOURCE}/org/cometd/ReloadExtension.js ${COMETD_JS_DIR}
    cp ${COMETD_JS_SOURCE}/org/cometd/TimeStampExtension.js ${COMETD_JS_DIR}
    cp ${COMETD_JS_SOURCE}/org/cometd/TimeSyncExtension.js ${COMETD_JS_DIR}

    cd ${COMETD_JS_DIR}

    cat <<EOF > ${COMETD_JS_DIR}/package.json
{
  "name": "cometd",
  "version": "${VERSION}",
  "main": "cometd.js"
}
EOF

    git add .
    git commit -m "Release ${VERSION}."
    git tag -am "Release ${VERSION}." ${VERSION}
    git push --follow-tags
fi
