package lib

import scalikejdbc._

object DbScheme {
  def create(implicit session: DBSession): Unit = {
    sql"""
         |CREATE TABLE IF NOT EXISTS FILES
         |(
         |    ID BIGINT AUTO_INCREMENT PRIMARY KEY NOT NULL,
         |    PATH VARCHAR(65536) NOT NULL,
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
       """.stripMargin.executeUpdate().apply()
  }
}
