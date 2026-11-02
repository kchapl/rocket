package rocket

import java.sql.{Connection, PreparedStatement, Types}

/**
 * Career Goal, Quarterly Objective and Outcome persistence. Ids are allocated
 * above the highest already present, including ids brought in by `import`.
 */
final class Store(connection: Connection) {

  // --- Career Goals -------------------------------------------------------

  def createGoal(title: String, why: Option[String], verification: List[String]): String = {
    val id = nextId("g", "goal")
    inTransaction {
      val ps = connection.prepareStatement(
        "INSERT INTO goal(id, title, why, archived) VALUES (?, ?, ?, 0)"
      )
      try {
        ps.setString(1, id)
        ps.setString(2, title)
        setOptional(ps, 3, why)
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
  def resolveGoal(needle: String): Either[String, String] =
    resolve(needle, "goal", "Career Goal")

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

  // --- Quarterly Objectives ----------------------------------------------

  /** Commits an Objective to the current Quarter under a Goal still being pursued. */
  def createObjective(
      title: String,
      goalNeedle: String,
      why: Option[String],
      verification: List[String]
  ): Either[String, String] =
    resolveGoal(goalNeedle).flatMap { goalId =>
      if (goalArchived(goalId)) Left(s"Career Goal $goalId is archived")
      else Right(commitObjective(goalId, title, why, verification))
    }

  private def commitObjective(
      goalId: String,
      title: String,
      why: Option[String],
      verification: List[String]
  ): String = {
    val id = nextId("obj", "objective")
    inTransaction {
      val ps = connection.prepareStatement(
        "INSERT INTO objective(id, title, goal_id, quarter, why) VALUES (?, ?, ?, ?, ?)"
      )
      try {
        ps.setString(1, id)
        ps.setString(2, title)
        ps.setString(3, goalId)
        ps.setString(4, Quarter.current)
        setOptional(ps, 5, why)
        ps.executeUpdate()
      } finally ps.close()
      insertVerification("objective_verification", "objective_id", id, verification)
    }
    id
  }

  def resolveObjective(needle: String): Either[String, String] =
    resolve(needle, "objective", "Quarterly Objective")

  // --- Outcomes -----------------------------------------------------------

  /**
   * Plans an Outcome in the current Quarter. Without an Objective it is
   * parentless — flagged as an emergency or unfiled — and an emergency Outcome
   * keeps neither a Why nor Verification statements.
   */
  def createOutcome(
      title: String,
      objectiveNeedle: Option[String],
      emergency: Boolean,
      why: Option[String],
      verification: List[String]
  ): Either[String, String] = {
    val quarter = Quarter.current
    val parent = objectiveNeedle match {
      case None => Right(None)
      case Some(needle) =>
        resolveObjective(needle).flatMap { id =>
          objectiveQuarter(id) match {
            case Some(committed) if committed == quarter => Right(Some(id))
            case Some(committed) =>
              Left(s"Quarterly Objective $id is committed in $committed, not $quarter")
            case None => Left(s"no Quarterly Objective $needle")
          }
        }
    }

    parent.map { objectiveId =>
      inTransaction {
        val id = nextId("out", "outcome")
        val ps = connection.prepareStatement(
          """INSERT INTO outcome(id, title, quarter, objective_id, emergency, why, rank)
            |VALUES (?, ?, ?, ?, ?, ?, ?)""".stripMargin
        )
        try {
          ps.setString(1, id)
          ps.setString(2, title)
          ps.setString(3, quarter)
          objectiveId match {
            case Some(parentId) => ps.setString(4, parentId)
            case None           => ps.setNull(4, Types.VARCHAR)
          }
          ps.setInt(5, if (emergency) 1 else 0)
          setOptional(ps, 6, if (emergency) None else why)
          ps.setInt(7, nextRank(quarter))
          ps.executeUpdate()
        } finally ps.close()
        insertVerification(
          "outcome_verification",
          "outcome_id",
          id,
          if (emergency) Nil else verification
        )
        id
      }
    }
  }

  /** The current Quarter's queue in rank order; `includeClosed` adds terminal Outcomes. */
  def backlog(includeClosed: Boolean): List[Outcome] = {
    val filter = if (includeClosed) "" else " AND status = 'open'"
    val ps = connection.prepareStatement(
      s"""SELECT id, title, quarter, objective_id, emergency, why, rank, status, stage
         |FROM outcome
         |WHERE quarter = ?$filter
         |ORDER BY rank""".stripMargin
    )
    val rows = List.newBuilder[
      (String, String, String, Option[String], Boolean, Option[String], Int, Status, Stage)
    ]
    try {
      ps.setString(1, Quarter.current)
      val rs = ps.executeQuery()
      while (rs.next()) {
        rows += (
          (
            rs.getString("id"),
            rs.getString("title"),
            rs.getString("quarter"),
            Option(rs.getString("objective_id")),
            rs.getInt("emergency") == 1,
            Option(rs.getString("why")),
            rs.getInt("rank"),
            status(rs.getString("status")),
            stage(rs.getString("stage"))
          )
        )
      }
      rs.close()
    } finally ps.close()

    rows.result().map { case (id, title, quarter, objectiveId, emergency, why, rank, s, g) =>
      Outcome(
        id,
        title,
        quarter,
        objectiveId,
        emergency,
        why,
        verificationFor("outcome_verification", "outcome_id", id),
        rank,
        s,
        g
      )
    }
  }

  /** The Quarters present in the record: those stamped on Outcomes or Time Boxes. */
  def quarters: List[String] = {
    val ps = connection.prepareStatement(
      """SELECT quarter FROM (
        |  SELECT quarter FROM outcome
        |  UNION
        |  SELECT quarter FROM time_box
        |) ORDER BY quarter""".stripMargin
    )
    val out = List.newBuilder[String]
    try {
      val rs = ps.executeQuery()
      while (rs.next()) out += rs.getString("quarter")
      rs.close()
    } finally ps.close()
    out.result()
  }

  // --- Shared helpers -----------------------------------------------------

  private def resolve(needle: String, table: String, label: String): Either[String, String] = {
    val ps = connection.prepareStatement(s"SELECT id, title FROM $table")
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
      case Nil            => Left(s"no $label $needle")
      case (id, _) :: Nil => Right(id)
      case many           => Left(s"$needle matches ${many.map(_._1).mkString(", ")}")
    }
  }

  private def objectiveQuarter(id: String): Option[String] =
    stringColumn("SELECT quarter FROM objective WHERE id = ?", id)

  private def goalArchived(id: String): Boolean = {
    val ps = connection.prepareStatement("SELECT archived FROM goal WHERE id = ?")
    try {
      ps.setString(1, id)
      val rs = ps.executeQuery()
      val archived = rs.next() && rs.getInt("archived") == 1
      rs.close()
      archived
    } finally ps.close()
  }

  private def stringColumn(sql: String, id: String): Option[String] = {
    val ps = connection.prepareStatement(sql)
    try {
      ps.setString(1, id)
      val rs = ps.executeQuery()
      val found = if (rs.next()) Some(rs.getString(1)) else None
      rs.close()
      found
    } finally ps.close()
  }

  /** The next free slot at the end of the Quarter's contiguous 1..n queue. */
  private def nextRank(quarter: String): Int = {
    val ps = connection.prepareStatement(
      "SELECT COALESCE(MAX(rank), 0) + 1 FROM outcome WHERE quarter = ?"
    )
    try {
      ps.setString(1, quarter)
      val rs = ps.executeQuery()
      val rank = if (rs.next()) rs.getInt(1) else 1
      rs.close()
      rank
    } finally ps.close()
  }

  private def status(raw: String): Status =
    Status
      .parse(raw)
      .getOrElse(throw new IllegalStateException(s"unknown outcome status: $raw"))

  private def stage(raw: String): Stage =
    Stage
      .parse(raw)
      .getOrElse(throw new IllegalStateException(s"unknown outcome stage: $raw"))

  private def setOptional(ps: PreparedStatement, index: Int, value: Option[String]): Unit =
    value match {
      case Some(text) => ps.setString(index, text)
      case None       => ps.setNull(index, Types.VARCHAR)
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

  private def inTransaction[A](body: => A): A = {
    val previous = connection.getAutoCommit
    connection.setAutoCommit(false)
    try {
      val result = body
      connection.commit()
      result
    } catch {
      case error: Throwable =>
        connection.rollback()
        throw error
    } finally connection.setAutoCommit(previous)
  }
}
