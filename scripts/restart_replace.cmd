net stop rbackup-client &&
move conf conf.old &&
move lib lib.old &&
move public public.old &&
move %1\conf conf &&
move %1\lib lib &&
move %1\public public &&
net start rbackup-client