package lib.db

import com.typesafe.scalalogging.StrictLogging
import lib.{App, AppVersion}
import scalikejdbc._

//noinspection SqlNoDataSourceInspection
object DbScheme extends StrictLogging {
  def create(implicit session: DBSession): Unit = {
    val currentVersionStr = App.versionStr

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
         |    key varchar(200) primary key not null,
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

    if (App.version > AppVersion(0, 1, 3)) {
      if (sql"""select value from settings where key='db_version'""".map(_.string("value")).single().apply().isEmpty) {
        logger.info(s"Didn't found db_version in DB, setting to $currentVersionStr")

        sql"""
             |insert ignore into settings values ('db_version', ${currentVersionStr});
       """.stripMargin.executeUpdate().apply()
      }
    }
  }

  def truncateAll(implicit session: DBSession): Unit = {
    sql"""
         |SET REFERENTIAL_INTEGRITY FALSE;
         |
         |truncate table files;
         |truncate table settings;
         |truncate table backup_sets;
         |truncate table backup_sets_files;
         |
         |SET REFERENTIAL_INTEGRITY TRUE;
       """.stripMargin.executeUpdate().apply()
  }

  def dropAll(implicit session: DBSession): Unit = {
    sql"""
         |SET REFERENTIAL_INTEGRITY FALSE;
         |
         |drop table if exists files;
         |drop table if exists settings;
         |drop table if exists backup_sets;
         |drop table if exists backup_sets_files;
         |
         |SET REFERENTIAL_INTEGRITY TRUE;
       """.stripMargin.executeUpdate().apply()
  }
}
