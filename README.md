# RBackup client

This is Scala implementation of client for [RBackup](https://github.com/jendakol/rbackup).

Readme TBD :-)

## Build (release)
```
#!/usr/bin/fish

./.travis.sh

env VERSION=$argv[1] \
    SENTRY_DSN="https://abcd@sentry.io/1234" \
    sbt ";clean;setVersionInSources;setSentryDsnInSources;dist"
```

The SENTRY_DSN is optional.