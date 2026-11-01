package rocket

import java.sql.{Connection, Types}

/** Career Goal persistence. Ids are allocated above the highest already present. */
final class Store(connection: Connection) {

  def createGoal(title: String, why: Option[String], verification: List[String]): String = {
    val id = nextId("g", "goal")
    inTransaction {
      val ps = connection.prepareStatement(
        "INSERT INTO goal(id, title, why, archived) VALUES (?, ?, ?, 0)"
      )
      try {
        ps.setString(1, id)
        ps.setString(2, title)
        why match {
          case Some(text) => ps.setString(3, text)
          case None       => ps.setNull(3, Types.VARCHAR)
        }
        ps.executeUpdate()
      } finally ps.close()
      insertVerification("goal_verification", "goal_id", id, verification)
    }
    id
  }

  /** Goals in creation order; `archived` selects which half of the record to show. */
  def goals(archived: Boolean): List[CareerGoal] = {
    val ps = connection.prepareStatement(
      """SELECT id, title, why, archived FROM goal
        |WHERE archived = ?
        |ORDER BY CAST(SUBSTR(id, 2) AS INTEGER)""".stripMargin
    )
    val rows = List.newBuilder[(String, String, Option[String], Boolean)]
    try {
      ps.setInt(1, if (archived) 1 else 0)
      val rs = ps.executeQuery()
      while (rs.next()) {
        rows += (
          (
            rs.getString("id"),
            rs.getString("title"),
            Option(rs.getString("why")),
            rs.getInt("archived") == 1
          )
        )
      }
      rs.close()
    } finally ps.close()

    rows.result().map { case (id, title, why, isArchived) =>
      CareerGoal(id, title, why, verificationFor("goal_verification", "goal_id", id), isArchived)
    }
  }

  /**
   * Resolves a Goal from what the user typed: an exact id first, then a unique
   * case-insensitive prefix of either the id or the title.
   */
  def resolveGoal(needle: String): Either[String, String] = {
    val ps = connection.prepareStatement("SELECT id, title FROM goal")
    val rows = List.newBuilder[(String, String)]
    try {
      val rs = ps.executeQuery()
      while (rs.next()) rows += ((rs.getString("id"), rs.getString("title")))
      rs.close()
    } finally ps.close()

    val all = rows.result()
    val exact = all.filter { case (id, _) => id.equalsIgnoreCase(needle) }
    val candidates =
      if (exact.nonEmpty) exact
      else {
        val lower = needle.toLowerCase
        all.filter { case (id, title) =>
          id.toLowerCase.startsWith(lower) || title.toLowerCase.startsWith(lower)
        }
      }

    candidates match {
      case Nil           => Left(s"no Career Goal $needle")
      case (id, _) :: Nil => Right(id)
      case many          => Left(s"$needle matches ${many.map(_._1).mkString(", ")}")
    }
  }

  def setArchived(needle: String, archived: Boolean): Either[String, String] =
    resolveGoal(needle).map { id =>
      val ps = connection.prepareStatement("UPDATE goal SET archived = ? WHERE id = ?")
      try {
        ps.setInt(1, if (archived) 1 else 0)
        ps.setString(2, id)
        ps.executeUpdate()
      } finally ps.close()
      id
    }

  private def verificationFor(table: String, ownerColumn: String, ownerId: String): List[String] = {
    val ps = connection.prepareStatement(
      s"SELECT statement FROM $table WHERE $ownerColumn = ? ORDER BY ordinal"
    )
    val out = List.newBuilder[String]
    try {
      ps.setString(1, ownerId)
      val rs = ps.executeQuery()
      while (rs.next()) out += rs.getString("statement")
      rs.close()
    } finally ps.close()
    out.result()
  }

  private def insertVerification(
      table: String,
      ownerColumn: String,
      ownerId: String,
      statements: List[String]
  ): Unit =
    if (statements.nonEmpty) {
      val ps = connection.prepareStatement(
        s"INSERT INTO $table($ownerColumn, ordinal, statement) VALUES (?, ?, ?)"
      )
      try {
        statements.zipWithIndex.foreach { case (statement, index) =>
          ps.setString(1, ownerId)
          ps.setInt(2, index + 1)
          ps.setString(3, statement)
          ps.executeUpdate()
        }
      } finally ps.close()
    }

  private def nextId(prefix: String, table: String): String = {
    val ps = connection.prepareStatement(s"SELECT id FROM $table")
    var highest = 0
    try {
      val rs = ps.executeQuery()
      while (rs.next()) {
        val raw = rs.getString("id")
        if (raw.startsWith(prefix)) {
          raw.drop(prefix.length).toIntOption.foreach(n => if (n > highest) highest = n)
        }
      }
      rs.close()
    } finally ps.close()
    s"$prefix${highest + 1}"
  }

  private def inTransaction(body: => Unit): Unit = {
    val previous = connection.getAutoCommit
    connection.setAutoCommit(false)
    try {
      body
      connection.commit()
    } catch {
      case error: Throwable =>
        connection.rollback()
        throw error
    } finally connection.setAutoCommit(previous)
  }
}
