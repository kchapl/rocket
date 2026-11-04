package rocket

/**
 * Compact JSON, one line per read command: enough for a pipeline to read, and
 * no dependency for the native binary to carry.
 */
object Json {

  /** A JSON string, escaped so any title the record accepts survives `jq`. */
  def str(text: String): String = {
    val escaped = new StringBuilder("\"")
    text.foreach {
      case '"'          => escaped.append("\\\"")
      case '\\'         => escaped.append("\\\\")
      case '\n'         => escaped.append("\\n")
      case '\r'         => escaped.append("\\r")
      case '\t'         => escaped.append("\\t")
      case '\b'         => escaped.append("\\b")
      case '\f'         => escaped.append("\\f")
      case control if control < ' ' => escaped.append("\\u%04x".format(control.toInt))
      case other        => escaped.append(other)
    }
    escaped.append('"').toString
  }

  def num(value: Int): String = value.toString

  def bool(value: Boolean): String = value.toString

  /** A field that may be absent from the record, which JSON spells as `null`. */
  def optional(text: Option[String]): String = text.fold("null")(str)

  def obj(fields: (String, String)*): String =
    fields.map { case (name, value) => s"${str(name)}:$value" }.mkString("{", ",", "}")

  def arr(items: Iterable[String]): String = items.mkString("[", ",", "]")

  def goal(goal: CareerGoal): String =
    obj(
      "id" -> str(goal.id),
      "title" -> str(goal.title),
      "why" -> optional(goal.why),
      "verification" -> arr(goal.verification.map(str)),
      "archived" -> bool(goal.archived)
    )

  def objective(objective: QuarterlyObjective): String =
    obj(
      "id" -> str(objective.id),
      "title" -> str(objective.title),
      "goalId" -> str(objective.goalId),
      "quarter" -> str(objective.quarter),
      "why" -> optional(objective.why),
      "verification" -> arr(objective.verification.map(str))
    )

  def outcome(outcome: Outcome): String =
    obj(
      "id" -> str(outcome.id),
      "title" -> str(outcome.title),
      "quarter" -> str(outcome.quarter),
      "objectiveId" -> optional(outcome.objectiveId),
      "emergency" -> bool(outcome.emergency),
      "why" -> optional(outcome.why),
      "verification" -> arr(outcome.verification.map(str)),
      "rank" -> num(outcome.rank),
      "status" -> str(outcome.status.wire),
      "stage" -> str(outcome.stage.wire)
    )

  def idea(idea: Idea): String =
    obj(
      "id" -> str(idea.id),
      "text" -> str(idea.text),
      "disposition" -> str(idea.disposition.wire),
      "promotedTo" -> optional(idea.promotedTo),
      "capturedAt" -> str(idea.capturedAt)
    )

  def gaps(gaps: Gaps): String =
    obj(
      "quarter" -> str(gaps.quarter),
      "unalignedGoals" -> arr(gaps.unalignedGoals.map(goal)),
      "emptyObjectives" -> arr(gaps.emptyObjectives.map(objective)),
      "parentlessOutcomes" -> arr(gaps.parentlessOutcomes.map(outcome))
    )

  def lineage(lineage: Lineage): String =
    obj(
      "goal" -> lineage.goal.fold("null")(goal),
      "objective" -> lineage.objective.fold("null")(objective),
      "outcome" -> outcome(lineage.outcome)
    )
}
