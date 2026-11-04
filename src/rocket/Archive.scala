package rocket

/**
 * The portable document: rocket's whole record as JSON, ids and settings
 * included. It is deliberately rocket's own format, so a store can move between
 * machines today; `trajectory` compatibility is retrofitted at this boundary
 * later, never in the schema.
 */
object Archive {

  /** Marks a document this build understands, so a later format can say so. */
  val Format = "rocket"
  val SchemaVersion = 1

  def encode(document: Document): String =
    Json.obj(
      "format" -> Json.str(Format),
      "schemaVersion" -> Json.num(SchemaVersion),
      "carriedThrough" -> Json.str(document.carriedThrough),
      "goals" -> Json.arr(document.goals.map(Json.goal)),
      "objectives" -> Json.arr(document.objectives.map(Json.objective)),
      "outcomes" -> Json.arr(document.outcomes.map(Json.outcome)),
      "timeBoxes" -> Json.arr(document.timeBoxes.map(Json.timeBox)),
      "ideas" -> Json.arr(document.ideas.map(Json.idea))
    )

  def decode(text: String): Either[String, Document] =
    Json.parse(text).left.map(problem => s"not JSON: $problem").flatMap(decodeDocument)

  // --- Reading a document ------------------------------------------------

  private def decodeDocument(value: Json.JsonValue): Either[String, Document] =
    for {
      fields <- objectFields(value, "document")
      format <- stringField(fields, "format", "document")
      _ <-
        if (format == Format) Right(())
        else Left(s"not a $Format document: format is \"$format\"")
      version <- intField(fields, "schemaVersion", "document")
      _ <-
        if (version == SchemaVersion) Right(())
        else Left(s"unsupported schemaVersion $version; this rocket writes $SchemaVersion")
      carriedThrough <- stringField(fields, "carriedThrough", "document")
      _ <- quarterField(carriedThrough, "document")
      goals <- arrayField(fields, "goals", "document")(decodeGoal)
      objectives <- arrayField(fields, "objectives", "document")(decodeObjective)
      outcomes <- arrayField(fields, "outcomes", "document")(decodeOutcome)
      timeBoxes <- arrayField(fields, "timeBoxes", "document")(decodeTimeBox)
      ideas <- arrayField(fields, "ideas", "document")(decodeIdea)
      _ <- duplicates(goals.map(_.id), "Goal", "document")
      _ <- duplicates(objectives.map(_.id), "Quarterly Objective", "document")
      _ <- duplicates(outcomes.map(_.id), "Outcome", "document")
      _ <- duplicates(ideas.map(_.id), "Idea", "document")
      _ <- sharedQuarter(objectives, outcomes)
    } yield Document(carriedThrough, goals, objectives, outcomes, timeBoxes, ideas)

  private def decodeGoal(value: Json.JsonValue, where: String): Either[String, CareerGoal] =
    for {
      fields <- objectFields(value, where)
      id <- stringField(fields, "id", where)
      title <- stringField(fields, "title", where)
      why <- optionalStringField(fields, "why", where)
      verification <- stringsField(fields, "verification", where)
      archived <- boolField(fields, "archived", where)
    } yield CareerGoal(id, title, why, verification, archived)

  private def decodeObjective(
      value: Json.JsonValue,
      where: String
  ): Either[String, QuarterlyObjective] =
    for {
      fields <- objectFields(value, where)
      id <- stringField(fields, "id", where)
      title <- stringField(fields, "title", where)
      goalId <- stringField(fields, "goalId", where)
      quarter <- stringField(fields, "quarter", where)
      _ <- quarterField(quarter, where)
      why <- optionalStringField(fields, "why", where)
      verification <- stringsField(fields, "verification", where)
    } yield QuarterlyObjective(id, title, goalId, quarter, why, verification)

  private def decodeOutcome(value: Json.JsonValue, where: String): Either[String, Outcome] =
    for {
      fields <- objectFields(value, where)
      id <- stringField(fields, "id", where)
      title <- stringField(fields, "title", where)
      quarter <- stringField(fields, "quarter", where)
      _ <- quarterField(quarter, where)
      objectiveId <- optionalStringField(fields, "objectiveId", where)
      emergency <- boolField(fields, "emergency", where)
      _ <-
        if (emergency && objectiveId.nonEmpty)
          Left(s"$where: an emergency Outcome has no objectiveId")
        else Right(())
      why <- optionalStringField(fields, "why", where)
      _ <-
        if (emergency && why.nonEmpty) Left(s"$where: an emergency Outcome carries no why")
        else Right(())
      verification <- stringsField(fields, "verification", where)
      rank <- intField(fields, "rank", where)
      statusName <- stringField(fields, "status", where)
      status <- Status.parse(statusName).toRight(s"$where: unknown status \"$statusName\"")
      stageName <- stringField(fields, "stage", where)
      stage <- Stage.parse(stageName).toRight(s"$where: unknown stage \"$stageName\"")
    } yield Outcome(id, title, quarter, objectiveId, emergency, why, verification, rank, status, stage)

  private def decodeTimeBox(value: Json.JsonValue, where: String): Either[String, TimeBox] =
    for {
      fields <- objectFields(value, where)
      outcomeId <- stringField(fields, "outcomeId", where)
      quarter <- stringField(fields, "quarter", where)
      _ <- quarterField(quarter, where)
      recordedAt <- stringField(fields, "recordedAt", where)
    } yield TimeBox(outcomeId, quarter, recordedAt)

  private def decodeIdea(value: Json.JsonValue, where: String): Either[String, Idea] =
    for {
      fields <- objectFields(value, where)
      id <- stringField(fields, "id", where)
      text <- stringField(fields, "text", where)
      dispositionName <- stringField(fields, "disposition", where)
      disposition <- Disposition
        .parse(dispositionName)
        .toRight(s"$where: unknown disposition \"$dispositionName\"")
      promotedTo <- optionalStringField(fields, "promotedTo", where)
      _ <-
        if ((disposition == Disposition.Promoted) != promotedTo.nonEmpty)
          Left(s"$where: a promoted Idea records what it became, and only a promoted Idea does")
        else Right(())
      capturedAt <- stringField(fields, "capturedAt", where)
    } yield Idea(id, text, disposition, promotedTo, capturedAt)

  // --- Reading helpers ---------------------------------------------------

  private def objectFields(
      value: Json.JsonValue,
      where: String
  ): Either[String, Map[String, Json.JsonValue]] =
    value match {
      case Json.JsonObject(fields) => Right(fields)
      case _                       => Left(s"$where: expected an object")
    }

  private def field(
      fields: Map[String, Json.JsonValue],
      name: String,
      where: String
  ): Either[String, Json.JsonValue] =
    fields.get(name).toRight(s"$where: $name is missing")

  private def stringField(
      fields: Map[String, Json.JsonValue],
      name: String,
      where: String
  ): Either[String, String] =
    field(fields, name, where).flatMap {
      case Json.JsonString(value) => Right(value)
      case _                      => Left(s"$where: $name must be a string")
    }

  private def optionalStringField(
      fields: Map[String, Json.JsonValue],
      name: String,
      where: String
  ): Either[String, Option[String]] =
    fields.get(name) match {
      case None | Some(Json.JsonNull)   => Right(None)
      case Some(Json.JsonString(value)) => Right(Some(value))
      case Some(_)                      => Left(s"$where: $name must be a string or null")
    }

  private def boolField(
      fields: Map[String, Json.JsonValue],
      name: String,
      where: String
  ): Either[String, Boolean] =
    field(fields, name, where).flatMap {
      case Json.JsonBoolean(value) => Right(value)
      case _                       => Left(s"$where: $name must be true or false")
    }

  private def intField(
      fields: Map[String, Json.JsonValue],
      name: String,
      where: String
  ): Either[String, Int] =
    field(fields, name, where).flatMap {
      case Json.JsonNumber(number) if number.isValidInt => Right(number.toInt)
      case _                                            => Left(s"$where: $name must be a whole number")
    }

  private def stringsField(
      fields: Map[String, Json.JsonValue],
      name: String,
      where: String
  ): Either[String, List[String]] =
    field(fields, name, where).flatMap {
      case Json.JsonArray(items) =>
        items.zipWithIndex.foldLeft[Either[String, List[String]]](Right(Nil)) {
          case (collected, (Json.JsonString(text), _)) => collected.map(_ :+ text)
          case (_, (_, index))                         => Left(s"$where: $name[$index] must be a string")
        }
      case _ => Left(s"$where: $name must be an array of strings")
    }

  private def arrayField[A](
      fields: Map[String, Json.JsonValue],
      name: String,
      where: String
  )(decode: (Json.JsonValue, String) => Either[String, A]): Either[String, List[A]] =
    field(fields, name, where).flatMap {
      case Json.JsonArray(items) =>
        items.zipWithIndex.foldLeft[Either[String, List[A]]](Right(Nil)) {
          case (collected, (item, index)) =>
            for {
              done <- collected
              next <- decode(item, s"$where.$name[$index]")
            } yield done :+ next
        }
      case _ => Left(s"$where: $name must be an array")
    }

  private def quarterField(name: String, where: String): Either[String, Unit] =
    if (Quarter.index(name).isEmpty) Left(s"$where: not a Quarter: $name") else Right(())

  private def duplicates(ids: List[String], label: String, where: String): Either[String, Unit] =
    ids
      .groupBy(identity)
      .collectFirst {
        case (id, copies) if copies.size > 1 => s"$where: $label $id appears ${copies.size} times"
      }
      .toLeft(())

  /** An Outcome and its Objective are per-Quarter commitments, and must agree. */
  private def sharedQuarter(
      objectives: List[QuarterlyObjective],
      outcomes: List[Outcome]
  ): Either[String, Unit] = {
    val quarters = objectives.map(objective => objective.id -> objective.quarter).toMap
    outcomes
      .collectFirst {
        case outcome if outcome.objectiveId.exists(id => quarters.get(id).exists(_ != outcome.quarter)) =>
          s"Outcome ${outcome.id} and its Objective sit in different Quarters"
      }
      .toLeft(())
  }
}
