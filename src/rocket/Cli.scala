package rocket

import java.io.PrintStream
import java.nio.file.Path
import scala.annotation.tailrec

/** The command surface. Returns the process exit code: 0 ok, 1 domain error, 2 usage error. */
final class Cli(dbPath: Path, out: PrintStream, err: PrintStream) {

  // --- The command surface, as data ---------------------------------------

  /**
   * Each noun's slice of the surface is declared once here and rendered twice:
   * as part of the whole surface, and as the noun's own help. Both read the
   * same lines, so a verb added for one cannot go missing from the other.
   */
  private object Surface {

    final case class Noun(name: String, summary: String, commands: List[String])

    /** Nouns sharing a heading; `None` prints no heading, for utility commands. */
    final case class Group(heading: Option[String], nouns: List[Noun])

    val career = Noun(
      "career",
      "Career Goals: the long-term ambitions, archived once no longer pursued",
      List(
        "rocket career add <title> [--why <text>] [--verification <text>]...",
        "rocket career list [--archived] [--json]",
        "rocket career archive <id> | unarchive <id>",
        "rocket career rename <id> <title>"
      )
    )

    val quarter = Noun(
      "quarter",
      "the Quarter itself, and the Gaps in its plan",
      List(
        "rocket quarter [current | list] [--json]",
        "rocket quarter review [quarter] [--json]"
      )
    )

    val objective = Noun(
      "objective",
      "Quarterly Objectives: what is committed to within the Quarter",
      List(
        "rocket objective add <title> --goal <id> [--why <text>] [--verification <text>]...",
        "rocket objective drop <id>",
        "rocket objective rename <id> <title>"
      )
    )

    val outcome = Noun(
      "outcome",
      "Outcomes: the units of work, filed under an Objective or standing outside one",
      List(
        "rocket outcome add [--objective <id> | --emergency] <title> [--why <text>] [--verification <text>]...",
        "rocket outcome parent <outcome-id> <objective-id>",
        "rocket outcome rename <id> <title>"
      )
    )

    val inbox = Noun(
      "inbox",
      "Ideas: thought captured before it is committed to anything",
      List(
        "rocket inbox add <text>",
        "rocket inbox list [--all] [--json]",
        "rocket inbox promote <id> [--to-goal | --to-obj <goal-id> | --to-out]",
        "rocket inbox discard <id>"
      )
    )

    val trace = Noun(
      "trace",
      "the upward lineage of an Outcome",
      List("rocket trace <outcome-id> [--json]")
    )

    val backlog = Noun(
      "backlog",
      "the Quarter's ranked queue of open Outcomes",
      List(
        "rocket backlog [list] [--all] [--json]",
        "rocket backlog reorder <id> <+N | -N>"
      )
    )

    val progress = Noun(
      "progress",
      "work in flight: Stage, Time Boxes and Status",
      List("rocket progress start|box|achieve|drop <id>")
    )

    val groups: List[Group] = List(
      Group(Some("Planning Mode"), List(career, quarter, objective, outcome, inbox)),
      Group(None, List(trace)),
      Group(Some("Throughput Mode"), List(backlog, progress))
    )

    val nouns: List[Noun] = groups.flatMap(_.nouns)

    def noun(name: String): Option[Noun] = nouns.find(_.name == name)
  }

  def run(args: List[String]): Int = args match {
    case Nil                    => usage(out); 0
    case ("--help" | "-h") :: _ => usage(out); 0
    case noun :: ("--help" | "-h") :: _ =>
      Surface.noun(noun) match {
        case Some(found) =>
          usage(out, found)
          0
        case None =>
          err.println(s"unknown command: $noun")
          usage(err)
          2
      }
    case _ :: _ =>
      rollover(json = args.contains("--json"))
      dispatch(args)
  }

  /**
   * Runs before any command, in either mode: the record moves Quarter at a
   * boundary, never part-way through a piece of work.
   */
  private def rollover(json: Boolean): Unit =
    withStore { store =>
      val stream = if (json) err else out
      store.rollover(Quarter.current).foreach(stream.println)
    }

  private def dispatch(args: List[String]): Int = args match {
    case "career" :: rest       => career(rest)
    case "quarter" :: rest      => quarter(rest)
    case "objective" :: rest    => objective(rest)
    case "outcome" :: rest      => outcome(rest)
    case "inbox" :: rest        => inbox(rest)
    case "trace" :: rest        => trace(rest)
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
    case "rename" :: id :: rest =>
      rename(id, rest, (store, needle, title) => store.renameGoal(needle, title))
    case ("archive" | "unarchive") :: _  =>
      err.println("usage: rocket career archive <id>")
      2
    case "rename" :: _ =>
      err.println("usage: rocket career rename <id> <title>")
      2
    case Nil =>
      help(err, "career")
      2
    case other :: _ =>
      err.println(s"unknown career command: $other")
      help(err, "career")
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

  private def careerList(args: List[String]): Int = {
    val json = args.contains("--json")
    val known = Set("--archived", "--json")
    args.find(flag => flag.startsWith("--") && !known(flag)) match {
      case Some(unknown) =>
        err.println(s"unknown flag: $unknown")
        2
      case None =>
        val archived = args.contains("--archived")
        withStore { store =>
          val goals = store.goals(archived)
          if (json) out.println(Json.arr(goals.map(Json.goal)))
          else if (goals.isEmpty) out.println(emptyMessage(archived))
          else goals.foreach(print)
          0
        }
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

  /** A rename is the same at every tier: resolve what was named, then rewrite its Claim. */
  private def rename(
      id: String,
      words: List[String],
      action: (Store, String, String) => Either[String, String]
  ): Int =
    words.find(_.startsWith("--")) match {
      case Some(unknown) =>
        err.println(s"unknown flag: $unknown")
        2
      case None =>
        val title = words.mkString(" ")
        if (title.isEmpty) {
          err.println("a title is required")
          2
        } else report(store => action(store, id, title).map(resolved => s"$resolved renamed"))
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

  private def quarter(args: List[String]): Int = {
    val json = args.contains("--json")
    args.filterNot(_ == "--json") match {
      case Nil | "current" :: Nil =>
        withStore { _ => out.println(if (json) Json.str(Quarter.current) else Quarter.current) }
        0
      case "list" :: Nil =>
        withStore { store =>
          val quarters = store.quarters
          if (json) out.println(Json.arr(quarters.map(Json.str)))
          else if (quarters.isEmpty) out.println("no Quarters in the record")
          else quarters.foreach(out.println)
        }
        0
      case "review" :: Nil         => quarterReview(Quarter.current, json)
      case "review" :: name :: Nil => quarterReview(name, json)
      case "review" :: _ =>
        err.println("usage: rocket quarter review [quarter]")
        2
      case other :: _ =>
        err.println(s"unknown quarter command: $other")
        help(err, "quarter")
        2
    }
  }

  /** Reports the reviewed Quarter's Gaps: Unaligned Goals, Empty Objectives, Parentless Outcomes. */
  private def quarterReview(name: String, json: Boolean): Int =
    if (Quarter.index(name).isEmpty) {
      err.println(s"not a Quarter: $name")
      2
    } else
      withStore { store =>
        val gaps = store.review(name)
        if (json) out.println(Json.gaps(gaps))
        else if (gaps.isEmpty) out.println(s"$name: no Gaps")
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
    case "add" :: rest          => objectiveAdd(rest)
    case "rename" :: id :: rest =>
      rename(id, rest, (store, needle, title) => store.renameObjective(needle, title))
    case "rename" :: _ =>
      err.println("usage: rocket objective rename <id> <title>")
      2
    case "drop" :: id :: Nil    => report(_.dropObjective(id))
    case "drop" :: _ =>
      err.println("usage: rocket objective drop <id>")
      2
    case Nil =>
      help(err, "objective")
      2
    case other :: _ =>
      err.println(s"unknown objective command: $other")
      help(err, "objective")
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
    case "add" :: rest          => outcomeAdd(rest)
    case "rename" :: id :: rest =>
      rename(id, rest, (store, needle, title) => store.renameOutcome(needle, title))
    case "rename" :: _ =>
      err.println("usage: rocket outcome rename <id> <title>")
      2
    case "parent" :: outcomeId :: objectiveId :: Nil =>
      report(_.parentOutcome(outcomeId, objectiveId))
    case "parent" :: _ =>
      err.println("usage: rocket outcome parent <outcome-id> <objective-id>")
      2
    case Nil =>
      help(err, "outcome")
      2
    case other :: _ =>
      err.println(s"unknown outcome command: $other")
      help(err, "outcome")
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

  // --- Planning Mode: Ideas -----------------------------------------------

  private def inbox(args: List[String]): Int = args match {
    case "add" :: rest          => inboxAdd(rest)
    case "list" :: rest         => inboxList(rest)
    case "promote" :: rest      => inboxPromote(rest)
    case "discard" :: id :: Nil => report(_.discardIdea(id))
    case "discard" :: _ =>
      err.println("usage: rocket inbox discard <id>")
      2
    case Nil =>
      help(err, "inbox")
      2
    case other :: _ =>
      err.println(s"unknown inbox command: $other")
      help(err, "inbox")
      2
  }

  private def inboxAdd(args: List[String]): Int =
    args.find(_.startsWith("--")) match {
      case Some(unknown) =>
        err.println(s"unknown flag: $unknown")
        2
      case None =>
        val text = args.mkString(" ")
        if (text.isEmpty) {
          err.println("an Idea needs text")
          2
        } else
          withStore { store =>
            out.println(store.captureIdea(text))
            0
          }
    }

  private def inboxList(args: List[String]): Int = {
    val json = args.contains("--json")
    val known = Set("--all", "--json")
    args.find(flag => flag.startsWith("--") && !known(flag)) match {
      case Some(unknown) =>
        err.println(s"unknown flag: $unknown")
        2
      case None =>
        val all = args.contains("--all")
        withStore { store =>
          val ideas = store.ideas(all)
          if (json) out.println(Json.arr(ideas.map(Json.idea)))
          else if (ideas.isEmpty) out.println(if (all) "no Ideas" else "no pending Ideas")
          else ideas.foreach(printIdea)
          0
        }
    }
  }

  private def inboxPromote(args: List[String]): Int = args match {
    case Nil =>
      err.println("usage: rocket inbox promote <id> [--to-goal | --to-obj <goal-id> | --to-out]")
      2
    case id :: flags =>
      parsePromotion(flags) match {
        case Left(problem) =>
          err.println(problem)
          2
        case Right(promotion) =>
          report(_.promoteIdea(id, promotion))
      }
  }

  private def parsePromotion(flags: List[String]): Either[String, Promotion] = flags match {
    case "--to-goal" :: Nil        => Right(Promotion.ToGoal)
    case "--to-out" :: Nil         => Right(Promotion.ToOutcome)
    case "--to-obj" :: goal :: Nil => Right(Promotion.ToObjective(goal))
    case "--to-obj" :: Nil          => Left("--to-obj needs a Career Goal")
    case Nil                        => Left(promoteHint)
    case _                          => Left(s"promote one Idea one way: $promoteHint")
  }

  private def promoteHint: String =
    "--to-goal, --to-obj <goal-id> or --to-out"

  private def printIdea(idea: Idea): Unit = {
    val verdict = idea.disposition match {
      case Disposition.Pending   => ""
      case Disposition.Discarded => "  (discarded)"
      case Disposition.Promoted =>
        idea.promotedTo.fold("  (promoted)")(target => s"  (promoted to $target)")
    }
    out.println(s"${idea.id}  ${idea.text}$verdict")
  }

  // --- Throughput Mode ----------------------------------------------------

  private def backlog(args: List[String]): Int = args match {
    case "reorder" :: id :: delta :: Nil => reorder(id, delta)
    case "reorder" :: _ =>
      err.println("usage: rocket backlog reorder <id> <+N | -N>")
      2
    case _ =>
      val json = args.contains("--json")
      val allowed = Set("list", "--all", "--json")
      val unknown = args.filterNot(allowed)
      if (unknown.nonEmpty) {
        err.println(s"unknown argument: ${unknown.head}")
        help(err, "backlog")
        2
      } else if (args.count(_ == "list") > 1) {
        err.println("usage: rocket backlog [list] [--all]")
        2
      } else {
        val all = args.contains("--all")
        withStore { store =>
          val outcomes = store.backlog(all)
          if (json) out.println(Json.arr(outcomes.map(Json.outcome)))
          else if (outcomes.isEmpty) {
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
    case ("start" | "box" | "achieve" | "drop") :: Nil =>
      err.println(s"usage: rocket progress ${args.head} <id>")
      2
    case Nil =>
      help(err, "progress")
      2
    case other :: _ =>
      err.println(s"unknown progress command: $other")
      help(err, "progress")
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

  private def printOutcome(outcome: Outcome): Unit =
    out.println(f"${outcome.rank}%d  ${outcome.id}  ${outcome.title}${markers(outcome)}")

  /** The parenthesised facts an Outcome line carries: what is in flight and what is over. */
  private def markers(outcome: Outcome): String = {
    val doing = if (outcome.stage == Stage.Doing) "  (doing)" else ""
    val emergency = if (outcome.emergency) "  (emergency)" else ""
    val closed = outcome.status match {
      case Status.Open => ""
      case other       => s"  (${other.wire})"
    }
    doing + emergency + closed
  }

  // --- Utility & Portability ----------------------------------------------

  /** Shows the whole chain the Outcome hangs from, indented from its Goal down to itself. */
  private def trace(args: List[String]): Int = {
    val json = args.contains("--json")
    args.filterNot(_ == "--json") match {
      case flag :: _ if flag.startsWith("-") =>
        err.println(s"unknown argument: $flag")
        help(err, "trace")
        2
      case id :: Nil =>
        withStore { store =>
          store.lineage(id) match {
            case Left(problem) =>
              err.println(problem)
              1
            case Right(lineage) =>
              if (json) out.println(Json.lineage(lineage)) else printLineage(lineage)
              0
          }
        }
      case Nil =>
        help(err, "trace")
        2
      case _ =>
        err.println("trace takes one Outcome id")
        help(err, "trace")
        2
    }
  }

  private def printLineage(lineage: Lineage): Unit = {
    var depth = 0
    lineage.goal.foreach { goal =>
      out.println(s"${goal.id}  ${goal.title}")
      depth = 1
    }
    lineage.objective.foreach { objective =>
      out.println(s"${indent(depth)}${objective.id}  ${objective.title}")
      depth += 1
    }
    val parentless =
      if (lineage.outcome.objectiveId.isEmpty && !lineage.outcome.emergency) "  (parentless)"
      else ""
    out.println(
      s"${indent(depth)}${lineage.outcome.id}  ${lineage.outcome.title}${markers(lineage.outcome)}$parentless"
    )
  }

  private def indent(depth: Int): String = "  " * depth

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
    Surface.groups.foreach { group =>
      stream.println()
      group.heading.foreach(heading => stream.println(heading))
      group.nouns.foreach(noun => noun.commands.foreach(line => stream.println(s"  $line")))
    }
  }

  /** One noun's own help, rendering the same lines the whole surface holds for it. */
  private def usage(stream: PrintStream, noun: Surface.Noun): Unit = {
    stream.println(s"rocket ${noun.name} - ${noun.summary}")
    stream.println()
    noun.commands.foreach(line => stream.println(s"  $line"))
  }

  private def help(stream: PrintStream, noun: String): Unit =
    Surface.noun(noun).foreach(found => usage(stream, found))
}
