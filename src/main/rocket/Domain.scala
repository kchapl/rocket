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

object Outcome {

  /** A field an Emergency Outcome's shape forbids; planning reports each one it dropped. */
  enum Field {
    case Objective
    case Why
    case Verification
  }

  /**
   * What an Outcome would be with this shape, before it has an id or a place in
   * the queue. An emergency keeps no Objective, Why or Verification; `discarded`
   * names what the caller asked for anyway, so a door can refuse rather than
   * silently drop what someone wrote. `objectiveId` is whatever the door holds —
   * a needle from the command line, an id in a document — and only the store
   * resolves it.
   */
  final case class Planned(
      title: String,
      quarter: String,
      objectiveId: Option[String],
      emergency: Boolean,
      why: Option[String],
      verification: List[String],
      discarded: Set[Field]
  ) {

    /** The one sentence for the shape this plan had to bend; `None` when it bent nothing. */
    def refusal: Option[String] = {
      val parts = List(
        Option.when(discarded(Field.Objective))("no Objective"),
        Option.when(discarded(Field.Why))("no Why"),
        Option.when(discarded(Field.Verification))("no Verification")
      ).flatten
      parts match {
        case Nil => None
        case one :: Nil => Some(s"an emergency Outcome carries $one")
        case several =>
          Some(
            s"an emergency Outcome carries ${several.init.mkString(", ")} and ${several.last}"
          )
      }
    }

    def stored(id: String, rank: Int): Outcome =
      Outcome(
        id,
        title,
        quarter,
        objectiveId,
        emergency,
        why,
        verification,
        rank,
        Status.Open,
        Stage.Todo
      )
  }

  def planned(
      title: String,
      quarter: String,
      objectiveId: Option[String],
      emergency: Boolean,
      why: Option[String],
      verification: List[String]
  ): Planned =
    if (!emergency)
      Planned(title, quarter, objectiveId, emergency = false, why, verification, Set.empty)
    else {
      val discarded = Set(
        Option.when(objectiveId.nonEmpty)(Field.Objective),
        Option.when(why.nonEmpty)(Field.Why),
        Option.when(verification.nonEmpty)(Field.Verification)
      ).flatten
      Planned(title, quarter, None, emergency = true, None, Nil, discarded)
    }

  /**
   * The Outcome as it stands once filed under `objective`: no longer an emergency,
   * and still holding whatever Why it had (an emergency's is empty). A parent in
   * another Quarter is refused, because an Outcome and its Objective are
   * per-Quarter commitments.
   */
  def reparented(outcome: Outcome, objective: QuarterlyObjective): Either[String, Outcome] =
    if (objective.quarter != outcome.quarter)
      Left(
        s"Quarterly Objective ${objective.id} is committed in ${objective.quarter}, not ${outcome.quarter}"
      )
    else Right(outcome.copy(objectiveId = Some(objective.id), emergency = false))
}

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

/** A completed Time Box as stored: effort already spent, stamped with its Quarter. */
final case class TimeBox(outcomeId: String, quarter: String, recordedAt: String)

/**
 * The whole record as one portable value: every row of every kind, plus the
 * Quarter the store is known to be current through.
 */
final case class Document(
    carriedThrough: String,
    goals: List[CareerGoal],
    objectives: List[QuarterlyObjective],
    outcomes: List[Outcome],
    timeBoxes: List[TimeBox],
    ideas: List[Idea]
) {
  def size: Int = goals.size + objectives.size + outcomes.size + timeBoxes.size + ideas.size
}

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

/**
 * What a write did, as facts rather than a sentence: the ids and counts the
 * record now reflects. `Report` turns one into the line a person reads.
 */
enum Change {
  case Created(id: String)
  case Renamed(id: String)
  case Archived(id: String, archived: Boolean)
  case OutcomeStarted(id: String)
  case TimeBoxesRecorded(id: String, count: Int)
  case OutcomeAchieved(id: String)
  case OutcomeDropped(id: String)
  case ObjectiveDropped(id: String, count: Int)
  case OutcomeParented(outcomeId: String, objectiveId: String)
  case BacklogReordered(id: String, rank: Int)
  case IdeaPromoted(ideaId: String, targetId: String)
  case IdeaDiscarded(id: String)
  case QuartersClosed(from: String, through: String, carried: Int, current: String)
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
