#!/usr/bin/env bash

function wait_for_service() {
    n=0
    until [ $n -ge 30 ]
    do
      echo -e "Waiting for rbackup"

      if [ "$(curl "http://localhost:3369/status" 2>/dev/null)" == "{\"status\": \"RBackup running\"}" ]; then
        break
      fi

      n=$[$n+1]
      sleep 2
    done

    if [ $n -ge 30 ]; then
      exit 1
    fi

    echo -e "rbackup ready"
}

function client_test() {
    cd ci-tests  && \
     docker-compose up -d --build --force-recreate && \
     wait_for_service && \
     cd .. && \
     sbt test && \
     docker-compose down
}

client_test &&
  if $(test "${TRAVIS_REPO_SLUG}" == "jendakol/rbackup-scala-client" && test "${TRAVIS_PULL_REQUEST}" == "false" && test "$TRAVIS_TAG" != ""); then
    sbt +publish
  else
    exit 0 # skipping publish, it's regular build
  fi
