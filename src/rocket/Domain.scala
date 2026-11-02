package rocket

import java.time.LocalDate

/** A Career Goal as stored: the top tier of the hierarchy. */
final case class CareerGoal(
    id: String,
    title: String,
    why: Option[String],
    verification: List[String],
    archived: Boolean
)

/** A Quarterly Objective as stored: the middle tier, committed to one Quarter. */
final case class QuarterlyObjective(
    id: String,
    title: String,
    goalId: String,
    quarter: String,
    why: Option[String],
    verification: List[String]
)

/** An Outcome's standing as a commitment. `achieved` and `dropped` are terminal (ADR 0001). */
enum Status(val wire: String) {
  case Open extends Status("open")
  case Achieved extends Status("achieved")
  case Dropped extends Status("dropped")
}

object Status {
  def parse(raw: String): Option[Status] = values.find(_.wire == raw)
}

/** Where a live Outcome's work stands. It has no terminal value (ADR 0001). */
enum Stage(val wire: String) {
  case Todo extends Stage("todo")
  case Doing extends Stage("doing")
}

object Stage {
  def parse(raw: String): Option[Stage] = values.find(_.wire == raw)
}

/** An Outcome as stored: the only unit of work, ranked within its Quarter's queue. */
final case class Outcome(
    id: String,
    title: String,
    quarter: String,
    objectiveId: Option[String],
    emergency: Boolean,
    why: Option[String],
    verification: List[String],
    rank: Int,
    status: Status,
    stage: Stage
)

/** What became of an Idea. `promoted` and `discarded` are final (ADR 0002). */
enum Disposition(val wire: String) {
  case Pending extends Disposition("pending")
  case Promoted extends Disposition("promoted")
  case Discarded extends Disposition("discarded")
}

object Disposition {
  def parse(raw: String): Option[Disposition] = values.find(_.wire == raw)
}

/** A raw thought as stored: no Quarter, no parent, and nothing committed by capturing it. */
final case class Idea(
    id: String,
    text: String,
    disposition: Disposition,
    promotedTo: Option[String],
    capturedAt: String
)

/** What an Idea is being promoted into. */
enum Promotion {
  case ToGoal
  case ToObjective(goal: String)
  case ToOutcome
}

/** An Outcome's whole upward chain: itself, its Objective and that Objective's Goal. */
final case class Lineage(
    outcome: Outcome,
    objective: Option[QuarterlyObjective],
    goal: Option[CareerGoal]
)

/** The Gaps a reviewed Quarter surfaces: holes in the plan, never stored. */
final case class Gaps(
    quarter: String,
    unalignedGoals: List[CareerGoal],
    emptyObjectives: List[QuarterlyObjective],
    parentlessOutcomes: List[Outcome]
) {
  def isEmpty: Boolean =
    unalignedGoals.isEmpty && emptyObjectives.isEmpty && parentlessOutcomes.isEmpty
}

/** Quarters are values, never rows: `YYYY-Qn`, derived from a calendar date. */
object Quarter {

  private val Name = "^(\\d{4})-Q([1-4])$".r

  def of(date: LocalDate): String = {
    val quarter = (date.getMonthValue - 1) / 3 + 1
    s"${date.getYear}-Q$quarter"
  }

  def current: String = of(LocalDate.now())

  /** A Quarter as a count of Quarters since year 0: orders them and lets them be counted apart. */
  def index(name: String): Option[Int] = name match {
    case Name(year, quarter) => year.toIntOption.map(year => year * 4 + quarter.toInt - 1)
    case _                   => None
  }

  def at(index: Int): String = s"${index / 4}-Q${index % 4 + 1}"
}
