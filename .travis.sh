#!/usr/bin/env bash

function wait_for_server() {
    n=0
    until [ $n -ge 30 ]
    do
      echo -e "Waiting for RBackup"

      if [ "$(curl "http://$RBACKUP_IP:3369/status" 2>/dev/null | jq -r .status)" == "RBackup running" ]; then
        break
      fi

      n=$[$n+1]
      sleep 2
    done

    if [ $n -ge 30 ]; then
      exit 1
    fi

    echo -e "RBackup ready"
}

function client_test() {
    cd ci-tests  && \
     docker-compose up -d --build --force-recreate && \
     export RBACKUP_IP=$(docker inspect citests_tests_1 | jq -r '.[0] | .NetworkSettings.Ports."3369/tcp" | .[0].HostIp') && \
     wait_for_server && \
     cd .. && \
     mkdir public && mkdir public/bundle && touch public/bundle/js.bundle.txt && touch public/bundle/style.bundle.txt && \
     sbt ";clean;test" && \
     cd ci-tests  && \
     docker-compose down
}

client_test
