echo Stopping the service...
net stop rbackup-client
del RUNNING_PID > NUL

echo Updating files...

if exist %1\conf (
    rd /s /q conf.old > NUL 2>&1
    move conf conf.old
    move %1\conf conf
) && ^
if exist %1\lib (
    rd /s /q lib.old > NUL 2>&1
    move lib lib.old
    move %1\lib lib
) && ^
if exist %1\public (
    rd /s /q public.old > NUL 2>&1
    move public public.old
    move %1\public public
) && ^
if exist %1\restart_replace.cmd (
    del restart_replace.cmd.old > NUL 2>&1
    move restart_replace.cmd restart_replace.cmd.old
    move %1\restart_replace.cmd restart_replace.cmd
)

echo Starting the service...

net start rbackup-client && ^
rd /s /q %1 > NUL 2>&1