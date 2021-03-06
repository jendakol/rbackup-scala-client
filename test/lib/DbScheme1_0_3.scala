package lib

import scalikejdbc._

object DbScheme1_0_3 {
  def create(implicit session: DBSession): Unit = {
    sql"""
         |CREATE TABLE IF NOT EXISTS FILES
         |(
         |    PATH VARCHAR(65536) PRIMARY KEY NOT NULL,
         |    LAST_MODIFIED TIMESTAMP NOT NULL,
         |    SIZE BIGINT NOT NULL,
         |    REMOTE_FILE TEXT
         |);
         |
         |CREATE UNIQUE INDEX IF NOT EXISTS "files_PATH_uindex" ON FILES (PATH);
         |
         |create table if not exists settings
         |(
         |    key varchar(200) not null,
         |    value varchar(65536) not null
         |);
         |
         |create table if not exists backup_sets
         |(
         |    id int primary key auto_increment not null,
         |    name varchar(200) not null,
         |    frequency int not null default 360,
         |    last_execution DATETIME,
         |    processing bool default false not null
         |);
         |
         |create table if not exists backup_sets_files
         |(
         |    path VARCHAR(65536) NOT NULL,
         |    set_id int not null,
         |    foreign key (set_id) references backup_sets(id) on delete cascade on update cascade,
         |    primary key (path, set_id)
         |);
         |
       """.stripMargin.executeUpdate().apply()
  }

}
