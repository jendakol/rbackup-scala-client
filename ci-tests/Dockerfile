FROM jendakol/rbackup:0.1

WORKDIR /
COPY . .

ENTRYPOINT /wait-for-it.sh mysql:3306 -s -t 30 && /start-server.sh
