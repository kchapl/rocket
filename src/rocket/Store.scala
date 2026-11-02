package rocket

import java.sql.{Connection, PreparedStatement, ResultSet, Types}
import java.time.{Instant, LocalDate, ZoneOffset}

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

  /**
   * Drops an Objective by dropping every open Outcome under it in one
   * transaction. The Objective row stays: it has no Status, so there is nothing
   * to record but the Outcomes it held.
   */
  def dropObjective(needle: String): Either[String, String] =
    resolveObjective(needle).map { id =>
      val dropped = inTransaction {
        val ps = connection.prepareStatement(
          "UPDATE outcome SET status = 'dropped' WHERE objective_id = ? AND status = 'open'"
        )
        try {
          ps.setString(1, id)
          ps.executeUpdate()
        } finally ps.close()
      }
      val noun = if (dropped == 1) "outcome" else "outcomes"
      s"$id dropped $dropped $noun"
    }

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

  /**
   * Sets the Outcome's Objective, whether it had none or a different one, and
   * clears the emergency flag. No record of the previous parent is kept, and
   * the two must share a Quarter: Objectives are per-Quarter commitments.
   */
  def parentOutcome(
      outcomeNeedle: String,
      objectiveNeedle: String
  ): Either[String, String] =
    for {
      outcomeId <- resolveOutcome(outcomeNeedle)
      outcome <- outcomeById(outcomeId).toRight(s"no Outcome $outcomeId")
      objectiveId <- resolveObjective(objectiveNeedle)
      committed <- objectiveQuarter(objectiveId).toRight(s"no Quarterly Objective $objectiveId")
      _ <-
        if (committed == outcome.quarter) Right(())
        else
          Left(s"Quarterly Objective $objectiveId is committed in $committed, not ${outcome.quarter}")
    } yield {
      reparent(outcomeId, objectiveId)
      s"$outcomeId parented to $objectiveId"
    }

  private def reparent(outcomeId: String, objectiveId: String): Unit = {
    val ps = connection.prepareStatement(
      "UPDATE outcome SET objective_id = ?, emergency = 0 WHERE id = ?"
    )
    try {
      ps.setString(1, objectiveId)
      ps.setString(2, outcomeId)
      ps.executeUpdate()
    } finally ps.close()
  }

  /** The current Quarter's queue in rank order; `includeClosed` adds terminal Outcomes. */
  def backlog(includeClosed: Boolean): List[Outcome] = {
    val filter = if (includeClosed) "" else " AND status = 'open'"
    query(
      s"""SELECT $OutcomeColumns
         |FROM outcome
         |WHERE quarter = ?$filter
         |ORDER BY rank""".stripMargin,
      _.setString(1, Quarter.current)
    )(readOutcome).map(withVerification)
  }

  def resolveOutcome(needle: String): Either[String, String] =
    resolve(needle, "outcome", "Outcome")

  // --- Throughput ---------------------------------------------------------

  /** Moves an open Outcome's Stage to `doing`; nothing else measures progress. */
  def startOutcome(needle: String): Either[String, String] =
    openOutcome(needle).map { outcome =>
      setStage(outcome.id, Stage.Doing)
      s"${outcome.id} ${Stage.Doing.wire}"
    }

  /**
   * Records a completed Time Box against an Outcome, stamped with the Quarter
   * the box happened in, and starts the work.
   */
  def recordBox(needle: String): Either[String, String] =
    openOutcome(needle).map { outcome =>
      val now = Instant.now()
      val quarter = Quarter.of(LocalDate.ofInstant(now, ZoneOffset.UTC))
      inTransaction {
        val ps = connection.prepareStatement(
          "INSERT INTO time_box(outcome_id, quarter, recorded_at) VALUES (?, ?, ?)"
        )
        try {
          ps.setString(1, outcome.id)
          ps.setString(2, quarter)
          ps.setString(3, now.toString)
          ps.executeUpdate()
        } finally ps.close()
        setStage(outcome.id, Stage.Doing)
      }
      val boxes = boxCount(outcome.id)
      s"${outcome.id} $boxes ${if (boxes == 1) "box" else "boxes"}"
    }

  def achieveOutcome(needle: String): Either[String, String] =
    closeOutcome(needle, Status.Achieved)

  def dropOutcome(needle: String): Either[String, String] =
    closeOutcome(needle, Status.Dropped)

  /**
   * Moves an open Outcome by `delta` places within the Quarter's queue and
   * renumbers the queue 1..n in one transaction. Terminal Outcomes keep the
   * slot their rank gives them.
   */
  def reorderOutcome(needle: String, delta: Int): Either[String, String] =
    resolveOutcome(needle).flatMap { id =>
      outcomeById(id) match {
        case None =>
          Left(s"no Outcome $id")
        case Some(outcome) if outcome.quarter != Quarter.current =>
          Left(s"$id sits in ${outcome.quarter}, not ${Quarter.current}")
        case Some(outcome) if outcome.status != Status.Open =>
          Left(s"$id is already ${outcome.status.wire}")
        case Some(_) =>
          val queue = rankedQueue(Quarter.current)
          val openIds = queue.collect { case (rowId, isOpen) if isOpen => rowId }
          val from = openIds.indexOf(id)
          val to = math.max(0, math.min(openIds.size - 1, from + delta))
          val reordered =
            if (from == to) openIds
            else openIds.patch(from, Nil, 1).patch(to, List(id), 0)
          Right(s"$id ${writeRanks(queue, reordered).indexOf(id) + 1}")
      }
    }

  /** The Quarters present in the record: those stamped on Outcomes or Time Boxes. */
  def quarters: List[String] =
    query(
      """SELECT quarter FROM (
        |  SELECT quarter FROM outcome
        |  UNION
        |  SELECT quarter FROM time_box
        |) ORDER BY quarter""".stripMargin,
      _ => ()
    )(_.getString("quarter"))

  // --- Planning gaps ------------------------------------------------------

  /**
   * The three kinds of hole in `quarter`'s plan: Career Goals with no Objective
   * committed in it, Objectives with no Outcomes, and Outcomes with no
   * Objective that are not emergencies. Archived Goals are never Gaps.
   */
  def review(quarter: String): Gaps = {
    val aligned = query(
      "SELECT goal_id FROM objective WHERE quarter = ?",
      _.setString(1, quarter)
    )(_.getString("goal_id")).toSet
    val unaligned = goals(archived = false).filterNot(goal => aligned(goal.id))
    val empty = objectivesIn(quarter).filterNot(objective => hasOutcomes(objective.id))
    val parentless = query(
      s"""SELECT $OutcomeColumns FROM outcome
         |WHERE quarter = ? AND objective_id IS NULL AND emergency = 0
         |ORDER BY rank""".stripMargin,
      _.setString(1, quarter)
    )(readOutcome).map(withVerification)
    Gaps(quarter, unaligned, empty, parentless)
  }

  private def objectivesIn(quarter: String): List[QuarterlyObjective] =
    query(
      """SELECT id, title, goal_id, quarter, why FROM objective
        |WHERE quarter = ?
        |ORDER BY CAST(SUBSTR(id, 4) AS INTEGER)""".stripMargin,
      _.setString(1, quarter)
    ) { rs =>
      QuarterlyObjective(
        rs.getString("id"),
        rs.getString("title"),
        rs.getString("goal_id"),
        rs.getString("quarter"),
        Option(rs.getString("why")),
        Nil
      )
    }.map { objective =>
      objective.copy(
        verification = verificationFor("objective_verification", "objective_id", objective.id)
      )
    }

  private def hasOutcomes(objectiveId: String): Boolean =
    query("SELECT 1 FROM outcome WHERE objective_id = ? LIMIT 1", _.setString(1, objectiveId))(
      _ => true
    ).nonEmpty

  // --- Quarter rollover ---------------------------------------------------

  /**
   * Carries the record into `current` when a boundary has passed, once however
   * many were crossed, and returns the notice to print when it moved.
   */
  def rollover(current: String): Option[String] =
    (setting("carried_through").flatMap(Quarter.index), Quarter.index(current)) match {
      case (Some(carriedThrough), Some(now)) if carriedThrough < now =>
        val carried = inTransaction {
          val count = carryForward(current)
          setSetting("carried_through", current)
          count
        }
        Some(notice(carriedThrough, now, current, carried))
      case _ => None
    }

  /**
   * Moves every open Outcome into `quarter`, re-committing the Objective of each
   * group that still has live work, and returns how many Outcomes moved. Time
   * Boxes stay in the Quarter they were recorded in, which is what makes
   * Carried In derivable. Achieved and dropped Outcomes stay behind.
   */
  private def carryForward(quarter: String): Int = {
    val carried = query(
      "SELECT id, objective_id FROM outcome WHERE status = 'open' ORDER BY quarter, rank",
      _ => ()
    )(rs => (rs.getString("id"), Option(rs.getString("objective_id"))))
    val alreadyHere =
      query("SELECT id FROM outcome WHERE quarter = ? ORDER BY rank", _.setString(1, quarter))(
        _.getString("id")
      )

    val reissued =
      carried.flatMap(_._2).distinct.map(old => old -> recommit(old, quarter)).toMap

    var spare = nextRank(quarter)
    carried.foreach { case (id, parent) =>
      val ps = connection.prepareStatement(
        "UPDATE outcome SET quarter = ?, objective_id = ?, rank = ? WHERE id = ?"
      )
      try {
        ps.setString(1, quarter)
        setOptional(ps, 2, parent.map(old => reissued.getOrElse(old, old)))
        ps.setInt(3, spare)
        ps.setString(4, id)
        ps.executeUpdate()
      } finally ps.close()
      spare += 1
    }

    renumber(quarter, carried.map(_._1) ++ alreadyHere)
    carried.size
  }

  /** Copies an Objective into `quarter` for the work carried into it, Verifications included. */
  private def recommit(oldObjectiveId: String, quarter: String): String = {
    val id = nextId("obj", "objective")
    val insert = connection.prepareStatement(
      """INSERT INTO objective(id, title, goal_id, quarter, why)
        |SELECT ?, title, goal_id, ?, why FROM objective WHERE id = ?""".stripMargin
    )
    try {
      insert.setString(1, id)
      insert.setString(2, quarter)
      insert.setString(3, oldObjectiveId)
      insert.executeUpdate()
    } finally insert.close()

    val copy = connection.prepareStatement(
      """INSERT INTO objective_verification(objective_id, ordinal, statement)
        |SELECT ?, ordinal, statement FROM objective_verification WHERE objective_id = ?""".stripMargin
    )
    try {
      copy.setString(1, id)
      copy.setString(2, oldObjectiveId)
      copy.executeUpdate()
    } finally copy.close()
    id
  }

  private def notice(start: Int, end: Int, current: String, carried: Int): String = {
    val closed =
      if (end - start == 1) Quarter.at(start)
      else s"${Quarter.at(start)}–${Quarter.at(end - 1)}"
    val noun = if (carried == 1) "open outcome" else "open outcomes"
    s"Notice: $closed closed. Carried forward $carried $noun into $current."
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

  private def statusOf(raw: String): Status =
    Status
      .parse(raw)
      .getOrElse(throw new IllegalStateException(s"unknown outcome status: $raw"))

  private def stageOf(raw: String): Stage =
    Stage
      .parse(raw)
      .getOrElse(throw new IllegalStateException(s"unknown outcome stage: $raw"))

  private def setOptional(ps: PreparedStatement, index: Int, value: Option[String]): Unit =
    value match {
      case Some(text) => ps.setString(index, text)
      case None       => ps.setNull(index, Types.VARCHAR)
    }

  private val OutcomeColumns =
    "id, title, quarter, objective_id, emergency, why, rank, status, stage"

  private def readOutcome(rs: ResultSet): Outcome =
    Outcome(
      rs.getString("id"),
      rs.getString("title"),
      rs.getString("quarter"),
      Option(rs.getString("objective_id")),
      rs.getInt("emergency") == 1,
      Option(rs.getString("why")),
      Nil,
      rs.getInt("rank"),
      statusOf(rs.getString("status")),
      stageOf(rs.getString("stage"))
    )

  private def withVerification(outcome: Outcome): Outcome =
    outcome.copy(verification = verificationFor("outcome_verification", "outcome_id", outcome.id))

  private def outcomeById(id: String): Option[Outcome] =
    query(s"SELECT $OutcomeColumns FROM outcome WHERE id = ?", _.setString(1, id))(readOutcome)
      .headOption

  /** The Outcome a progress command targets: it must still be open. */
  private def openOutcome(needle: String): Either[String, Outcome] =
    resolveOutcome(needle).flatMap { id =>
      outcomeById(id) match {
        case None => Left(s"no Outcome $id")
        case Some(outcome) if outcome.status != Status.Open =>
          Left(s"$id is already ${outcome.status.wire}")
        case Some(outcome) => Right(outcome)
      }
    }

  private def closeOutcome(needle: String, status: Status): Either[String, String] =
    openOutcome(needle).map { outcome =>
      val ps = connection.prepareStatement("UPDATE outcome SET status = ? WHERE id = ?")
      try {
        ps.setString(1, status.wire)
        ps.setString(2, outcome.id)
        ps.executeUpdate()
      } finally ps.close()
      s"${outcome.id} ${status.wire}"
    }

  private def setStage(id: String, stage: Stage): Unit = {
    val ps = connection.prepareStatement("UPDATE outcome SET stage = ? WHERE id = ?")
    try {
      ps.setString(1, stage.wire)
      ps.setString(2, id)
      ps.executeUpdate()
    } finally ps.close()
  }

  private def boxCount(outcomeId: String): Int =
    query("SELECT COUNT(*) FROM time_box WHERE outcome_id = ?", _.setString(1, outcomeId))(
      _.getInt(1)
    ).headOption.getOrElse(0)

  private def rankedQueue(quarter: String): List[(String, Boolean)] =
    query("SELECT id, status FROM outcome WHERE quarter = ? ORDER BY rank", _.setString(1, quarter))(
      rs => (rs.getString("id"), rs.getString("status") == Status.Open.wire)
    )

  /**
   * Rewrites the Quarter's ranks 1..n, keeping terminal Outcomes in the slots
   * they occupied, and returns the resulting order.
   */
  private def writeRanks(queue: List[(String, Boolean)], openIds: List[String]): List[String] =
    inTransaction {
      var remaining = openIds
      val ordered = queue.map { case (rowId, isOpen) =>
        if (isOpen) {
          val next = remaining.head
          remaining = remaining.tail
          next
        } else rowId
      }
      renumber(Quarter.current, ordered)
      ordered
    }

  /**
   * Gives `ordered` the ranks 1..n in `quarter`. Ranks shift through spare
   * negative values first: SQLite cannot defer `UNIQUE(quarter, rank)`.
   */
  private def renumber(quarter: String, ordered: List[String]): Unit = {
    val negate = connection.prepareStatement("UPDATE outcome SET rank = -rank WHERE quarter = ?")
    try {
      negate.setString(1, quarter)
      negate.executeUpdate()
    } finally negate.close()

    val assign = connection.prepareStatement("UPDATE outcome SET rank = ? WHERE id = ?")
    try {
      ordered.zipWithIndex.foreach { case (rowId, index) =>
        assign.setInt(1, index + 1)
        assign.setString(2, rowId)
        assign.executeUpdate()
      }
    } finally assign.close()
  }

  private def setting(key: String): Option[String] =
    query("SELECT value FROM setting WHERE key = ?", _.setString(1, key))(_.getString("value"))
      .headOption

  private def setSetting(key: String, value: String): Unit = {
    val ps = connection.prepareStatement("INSERT OR REPLACE INTO setting(key, value) VALUES (?, ?)")
    try {
      ps.setString(1, key)
      ps.setString(2, value)
      ps.executeUpdate()
    } finally ps.close()
  }

  private def query[A](sql: String, bind: PreparedStatement => Unit)(
      read: ResultSet => A
  ): List[A] = {
    val ps = connection.prepareStatement(sql)
    val rows = List.newBuilder[A]
    try {
      bind(ps)
      val rs = ps.executeQuery()
      while (rs.next()) rows += read(rs)
      rs.close()
    } finally ps.close()
    rows.result()
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
