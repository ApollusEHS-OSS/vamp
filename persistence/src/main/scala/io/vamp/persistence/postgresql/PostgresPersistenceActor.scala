package io.vamp.persistence.postgresql

import io.vamp.common.ClassMapper
import io.vamp.persistence.{ SqlPersistenceActor, SqlStatementProvider }

/**
 * Maps postgres to class mapper for lifter
 */
class PostgresPersistenceActorMapper extends ClassMapper {
  val name = "postgres"
  val clazz: Class[_] = classOf[PostgresPersistenceActor]
}

/**
 * Support for PostgresSQL
 */
class PostgresPersistenceActor extends SqlPersistenceActor with SqlStatementProvider {

  def insertStatement(): String = s"""insert into "$table" ("Record") values (?)"""

  def selectStatement(lastId: Long): String = s"""SELECT "ID", "Record" FROM "$table" WHERE "ID" > $lastId ORDER BY "ID" ASC"""

  // In Postgres the minvalue of a select statement fetch is 0
  val statementMinValue: Int = 0
}
