#!/usr/bin/env bash

sbt test &&
  if $(test ${TRAVIS_REPO_SLUG} == "jendakol/rbackup-scala-client" && test ${TRAVIS_PULL_REQUEST} == "false" && test "$TRAVIS_TAG" != ""); then
    sbt +publish
  else
    exit 0 # skipping publish, it's regular build
  fi
