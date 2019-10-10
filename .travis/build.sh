#!/bin/sh
curl -fsLO https://raw.githubusercontent.com/scijava/scijava-scripts/master/travis-build.sh
sh travis-build.sh $encrypted_c9bc32d0658c_key $encrypted_c9bc32d0658c_iv
