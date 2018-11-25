FROM jendakol/rbackup:latest

WORKDIR /
COPY . .

ENTRYPOINT /wait-for-it.sh mysql:3306 -s -t 30 && /start-rbackup.sh
