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

/** Quarters are values, never rows: `YYYY-Qn`, derived from a calendar date. */
object Quarter {

  def of(date: LocalDate): String = {
    val quarter = (date.getMonthValue - 1) / 3 + 1
    s"${date.getYear}-Q$quarter"
  }

  def current: String = of(LocalDate.now())
}
