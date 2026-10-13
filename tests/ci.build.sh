#!/bin/bash
source ./set-env.sh

if [[ -e ../target ]]; then
  # Replace, don't accumulate: every *-SNAPSHOT.jar in artifacts/ gets installed, so a jar left
  # from a previous version would be deployed next to the new one.
  mkdir -p ./artifacts
  rm -f ./artifacts/${MODULE_ID}-*-SNAPSHOT.jar
  cp -R ../target/*-SNAPSHOT.jar ./artifacts/
fi

version=$(node -p "require('./package.json').devDependencies['@jahia/cypress']")
echo Using @jahia/cypress@$version...
npx --yes --package @jahia/cypress@$version ci.build
