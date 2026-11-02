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

/** Quarters are values, never rows: `YYYY-Qn`, derived from a calendar date. */
object Quarter {

  def of(date: LocalDate): String = {
    val quarter = (date.getMonthValue - 1) / 3 + 1
    s"${date.getYear}-Q$quarter"
  }

  def current: String = of(LocalDate.now())
}
