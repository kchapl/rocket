package rocket

import java.io.PrintStream
import java.nio.file.Path
import scala.annotation.tailrec

/** The command surface. Returns the process exit code: 0 ok, 1 domain error, 2 usage error. */
final class Cli(dbPath: Path, out: PrintStream, err: PrintStream) {

  def run(args: List[String]): Int = args match {
    case Nil                    => usage(out); 0
    case ("--help" | "-h") :: _ => usage(out); 0
    case _ :: _ =>
      rollover()
      dispatch(args)
  }

  /**
   * Runs before any command, in either mode: the record moves Quarter at a
   * boundary, never part-way through a piece of work.
   */
  private def rollover(): Unit =
    withStore { store =>
      store.rollover(Quarter.current).foreach(out.println)
    }

  private def dispatch(args: List[String]): Int = args match {
    case "career" :: rest       => career(rest)
    case "quarter" :: rest      => quarter(rest)
    case "objective" :: rest    => objective(rest)
    case "outcome" :: rest      => outcome(rest)
    case "backlog" :: rest      => backlog(rest)
    case "progress" :: rest     => progress(rest)
    case other :: _             =>
      err.println(s"unknown command: $other")
      usage(err)
      2
    case Nil =>
      usage(out)
      0
  }

  // --- Planning Mode: Career Goals ----------------------------------------

  private def career(args: List[String]): Int = args match {
    case "add" :: rest                   => careerAdd(rest)
    case "list" :: rest                  => careerList(rest)
    case "archive" :: id :: Nil          => setArchived(id, archived = true)
    case "unarchive" :: id :: Nil        => setArchived(id, archived = false)
    case ("archive" | "unarchive") :: _  =>
      err.println("usage: rocket career archive <id>")
      2
    case Nil =>
      err.println("usage: rocket career <add|list|archive|unarchive>")
      2
    case other :: _ =>
      err.println(s"unknown career command: $other")
      usage(err)
      2
  }

  private def careerAdd(args: List[String]): Int =
    parseAddArgs(args) match {
      case Left(problem) =>
        err.println(problem)
        2
      case Right(parsed) =>
        withStore { store =>
          out.println(store.createGoal(parsed.title, parsed.why, parsed.verification))
          0
        }
    }

  private def careerList(args: List[String]): Int =
    args.find(flag => flag.startsWith("--") && flag != "--archived") match {
      case Some(unknown) =>
        err.println(s"unknown flag: $unknown")
        2
      case None =>
        val archived = args.contains("--archived")
        withStore { store =>
          val goals = store.goals(archived)
          if (goals.isEmpty) out.println(emptyMessage(archived))
          else goals.foreach(print)
          0
        }
    }

  private def setArchived(id: String, archived: Boolean): Int =
    withStore { store =>
      store.setArchived(id, archived) match {
        case Left(problem) =>
          err.println(problem)
          1
        case Right(resolved) =>
          out.println(if (archived) s"archived $resolved" else s"unarchived $resolved")
          0
      }
    }

  private def emptyMessage(archived: Boolean): String =
    if (archived) "no archived Career Goals" else "no Career Goals"

  private def print(goal: CareerGoal): Unit = {
    val marker = if (goal.archived) "  (archived)" else ""
    out.println(s"${goal.id}  ${goal.title}$marker")
    goal.why.foreach(text => out.println(s"      why: $text"))
    if (goal.verification.nonEmpty)
      out.println(s"      verification: ${goal.verification.mkString("; ")}")
  }

  // --- Planning Mode: Quarters and Objectives -----------------------------

  private def quarter(args: List[String]): Int = args match {
    case Nil | "current" :: Nil =>
      withStore { _ => out.println(Quarter.current) }
      0
    case "list" :: Nil =>
      withStore { store =>
        val quarters = store.quarters
        if (quarters.isEmpty) out.println("no Quarters in the record")
        else quarters.foreach(out.println)
      }
      0
    case "review" :: Nil         => quarterReview(Quarter.current)
    case "review" :: name :: Nil => quarterReview(name)
    case "review" :: _ =>
      err.println("usage: rocket quarter review [quarter]")
      2
    case _ =>
      err.println("usage: rocket quarter [current | list | review [quarter]]")
      2
  }

  /** Reports the reviewed Quarter's Gaps: Unaligned Goals, Empty Objectives, Parentless Outcomes. */
  private def quarterReview(name: String): Int =
    if (Quarter.index(name).isEmpty) {
      err.println(s"not a Quarter: $name")
      2
    } else
      withStore { store =>
        val gaps = store.review(name)
        if (gaps.isEmpty) out.println(s"$name: no Gaps")
        else {
          out.println(name)
          section("Unaligned Goals", gaps.unalignedGoals.map(goal => s"${goal.id}  ${goal.title}"))
          section(
            "Empty Objectives",
            gaps.emptyObjectives.map(objective =>
              s"${objective.id}  ${objective.title}  (${objective.goalId})"
            )
          )
          section(
            "Parentless Outcomes",
            gaps.parentlessOutcomes.map(outcome => s"${outcome.id}  ${outcome.title}")
          )
        }
        0
      }

  private def section(heading: String, lines: List[String]): Unit =
    if (lines.nonEmpty) {
      out.println(s"  $heading")
      lines.foreach(line => out.println(s"    $line"))
    }

  private def objective(args: List[String]): Int = args match {
    case "add" :: rest            => objectiveAdd(rest)
    case "drop" :: id :: Nil      => report(_.dropObjective(id))
    case "drop" :: _ =>
      err.println("usage: rocket objective drop <id>")
      2
    case Nil =>
      err.println("usage: rocket objective add <title> --goal <id>")
      2
    case other :: _ =>
      err.println(s"unknown objective command: $other")
      usage(err)
      2
  }

  private def objectiveAdd(args: List[String]): Int =
    parseAddArgs(args, allowed = Set("--goal")) match {
      case Left(problem) =>
        err.println(problem)
        2
      case Right(parsed) =>
        parsed.goal match {
          case None =>
            err.println("an Objective needs --goal <id>")
            2
          case Some(goal) =>
            withStore { store =>
              store.createObjective(parsed.title, goal, parsed.why, parsed.verification) match {
                case Left(problem) =>
                  err.println(problem)
                  1
                case Right(id) =>
                  out.println(id)
                  0
              }
            }
        }
    }

  // --- Planning Mode: Outcomes --------------------------------------------

  private def outcome(args: List[String]): Int = args match {
    case "add" :: rest => outcomeAdd(rest)
    case "parent" :: outcomeId :: objectiveId :: Nil =>
      report(_.parentOutcome(outcomeId, objectiveId))
    case "parent" :: _ =>
      err.println("usage: rocket outcome parent <outcome-id> <objective-id>")
      2
    case Nil =>
      err.println("usage: rocket outcome add [--objective <id> | --emergency] <title>")
      2
    case other :: _ =>
      err.println(s"unknown outcome command: $other")
      usage(err)
      2
  }

  private def outcomeAdd(args: List[String]): Int =
    parseAddArgs(args, allowed = Set("--objective", "--emergency")) match {
      case Left(problem) =>
        err.println(problem)
        2
      case Right(parsed) if parsed.emergency && parsed.objective.nonEmpty =>
        err.println("an emergency Outcome has no --objective")
        2
      case Right(parsed)
          if parsed.emergency && (parsed.why.nonEmpty || parsed.verification.nonEmpty) =>
        err.println("an emergency Outcome carries no --why and no --verification")
        2
      case Right(parsed) =>
        withStore { store =>
          store.createOutcome(
            parsed.title,
            parsed.objective,
            parsed.emergency,
            parsed.why,
            parsed.verification
          ) match {
            case Left(problem) =>
              err.println(problem)
              1
            case Right(id) =>
              out.println(id)
              0
          }
        }
    }

  // --- Throughput Mode ----------------------------------------------------

  private def backlog(args: List[String]): Int = args match {
    case "reorder" :: id :: delta :: Nil => reorder(id, delta)
    case "reorder" :: _ =>
      err.println("usage: rocket backlog reorder <id> <+N | -N>")
      2
    case _ =>
      val unknown = args.filterNot(argument => argument == "list" || argument == "--all")
      if (unknown.nonEmpty) {
        err.println(s"unknown argument: ${unknown.head}")
        2
      } else if (args.count(_ == "list") > 1) {
        err.println("usage: rocket backlog [list] [--all]")
        2
      } else {
        val all = args.contains("--all")
        withStore { store =>
          val outcomes = store.backlog(all)
          if (outcomes.isEmpty) {
            val kind = if (all) "Outcomes" else "open Outcomes"
            out.println(s"no $kind in ${Quarter.current}")
          } else outcomes.foreach(printOutcome)
          0
        }
      }
  }

  private val Move = "^[+-]\\d+$".r

  private def reorder(id: String, raw: String): Int =
    if (!Move.matches(raw)) {
      err.println(s"$raw is not a move: write +N or -N")
      2
    } else
      raw.toIntOption match {
        case None =>
          err.println(s"$raw is out of range")
          2
        case Some(delta) =>
          report(_.reorderOutcome(id, delta))
      }

  private def progress(args: List[String]): Int = args match {
    case "start" :: id :: Nil   => report(_.startOutcome(id))
    case "box" :: id :: Nil     => report(_.recordBox(id))
    case "achieve" :: id :: Nil => report(_.achieveOutcome(id))
    case "drop" :: id :: Nil    => report(_.dropOutcome(id))
    case verb :: Nil =>
      err.println(s"usage: rocket progress $verb <id>")
      2
    case Nil =>
      err.println("usage: rocket progress <start|box|achieve|drop> <id>")
      2
    case other :: _ =>
      err.println(s"unknown progress command: $other")
      usage(err)
      2
  }

  private def report(action: Store => Either[String, String]): Int =
    withStore { store =>
      action(store) match {
        case Left(problem) =>
          err.println(problem)
          1
        case Right(message) =>
          out.println(message)
          0
      }
    }

  private def printOutcome(outcome: Outcome): Unit = {
    val doing = if (outcome.stage == Stage.Doing) "  (doing)" else ""
    val emergency = if (outcome.emergency) "  (emergency)" else ""
    val closed = outcome.status match {
      case Status.Open => ""
      case other       => s"  (${other.wire})"
    }
    out.println(f"${outcome.rank}%d  ${outcome.id}  ${outcome.title}$doing$emergency$closed")
  }

  // --- Plumbing -----------------------------------------------------------

  private def withStore[A](body: Store => A): A = {
    val connection = Db.connect(dbPath)
    try body(new Store(connection))
    finally connection.close()
  }

  private final case class ParsedAdd(
      title: String,
      why: Option[String],
      verification: List[String],
      goal: Option[String],
      objective: Option[String],
      emergency: Boolean
  )

  private object ParsedAdd {
    val empty: ParsedAdd = ParsedAdd("", None, Nil, None, None, false)
  }

  /**
   * Reads a title from the bare words plus `--why` and `--verification`;
   * `allowed` names the further flags this particular `add` accepts.
   */
  private def parseAddArgs(
      args: List[String],
      allowed: Set[String] = Set.empty
  ): Either[String, ParsedAdd] = {
    @tailrec
    def loop(
        rest: List[String],
        words: List[String],
        parsed: ParsedAdd
    ): Either[String, ParsedAdd] = rest match {
      case Nil =>
        Right(parsed.copy(title = words.mkString(" ")))
      case "--why" :: value :: tail =>
        loop(tail, words, parsed.copy(why = Some(value)))
      case "--verification" :: value :: tail =>
        loop(tail, words, parsed.copy(verification = parsed.verification :+ value))
      case flag :: Nil if flag == "--why" || flag == "--verification" =>
        Left(s"$flag needs a value")
      case flag :: value :: tail if flag == "--goal" || flag == "--objective" =>
        if (!allowed(flag)) Left(s"unknown flag: $flag")
        else if (flag == "--goal") loop(tail, words, parsed.copy(goal = Some(value)))
        else loop(tail, words, parsed.copy(objective = Some(value)))
      case flag :: Nil if flag == "--goal" || flag == "--objective" =>
        if (!allowed(flag)) Left(s"unknown flag: $flag") else Left(s"$flag needs a value")
      case "--emergency" :: tail =>
        if (allowed("--emergency")) loop(tail, words, parsed.copy(emergency = true))
        else Left("unknown flag: --emergency")
      case flag :: _ if flag.startsWith("--") =>
        Left(s"unknown flag: $flag")
      case word :: tail =>
        loop(tail, words :+ word, parsed)
    }

    loop(args, Nil, ParsedAdd.empty).flatMap { parsed =>
      if (parsed.title.isEmpty) Left("a title is required") else Right(parsed)
    }
  }

  private def usage(stream: PrintStream): Unit = {
    stream.println("rocket - planning and throughput for a career")
    stream.println()
    stream.println("Planning Mode")
    stream.println("  rocket career add <title> [--why <text>] [--verification <text>]...")
    stream.println("  rocket career list [--archived]")
    stream.println("  rocket career archive <id> | unarchive <id>")
    stream.println("  rocket quarter [current | list]")
    stream.println("  rocket quarter review [quarter]")
    stream.println("  rocket objective add <title> --goal <id> [--why <text>] [--verification <text>]...")
    stream.println("  rocket objective drop <id>")
    stream.println(
      "  rocket outcome add [--objective <id> | --emergency] <title> [--why <text>] [--verification <text>]..."
    )
    stream.println("  rocket outcome parent <outcome-id> <objective-id>")
    stream.println()
    stream.println("Throughput Mode")
    stream.println("  rocket backlog [list] [--all]")
    stream.println("  rocket backlog reorder <id> <+N | -N>")
    stream.println("  rocket progress start|box|achieve|drop <id>")
  }
}
