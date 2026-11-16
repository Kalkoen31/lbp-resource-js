#!/usr/bin/env bash
# Builds dist/lbp-resource.js from the cwlib sources in upstream/ and runs all tests.
# Needs JDK 17+, Maven and Node 18+.
set -euo pipefail
cd "$(dirname "$0")"

git submodule update --init upstream

# cwlib (and its parent pom) into the local Maven repository
mvn -B -q -f upstream/pom.xml -pl lib/cwlib -am install -DskipTests

# Java tests (write target/fixtures), then TeaVM -> target/javascript/cwlib.js
mvn -B package

node scripts/bundle.mjs
node --test test/*.test.mjs
