package rocket

import java.io.PrintStream
import java.nio.file.Path

/** The command surface. Returns the process exit code: 0 ok, 1 domain error, 2 usage error. */
final class Cli(dbPath: Path, out: PrintStream, err: PrintStream) {

  def run(args: List[String]): Int = args match {
    case Nil                    => usage(out); 0
    case ("--help" | "-h") :: _ => usage(out); 0
    case "career" :: rest       => career(rest)
    case other :: _             =>
      err.println(s"unknown command: $other")
      usage(err)
      2
  }

  private def career(args: List[String]): Int = args match {
    case "add" :: rest              => careerAdd(rest)
    case "list" :: rest             => careerList(rest)
    case "archive" :: id :: Nil     => setArchived(id, archived = true)
    case "unarchive" :: id :: Nil   => setArchived(id, archived = false)
    case ("archive" | "unarchive") :: _ =>
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
      case Right((title, why, verification)) =>
        withStore { store =>
          out.println(store.createGoal(title, why, verification))
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

  private def withStore[A](body: Store => A): A = {
    val connection = Db.connect(dbPath)
    try body(new Store(connection))
    finally connection.close()
  }

  private def parseAddArgs(
      args: List[String]
  ): Either[String, (String, Option[String], List[String])] = {
    var words = List.empty[String]
    var why: Option[String] = None
    var verification = List.empty[String]
    var rest = args
    var problem: Option[String] = None

    while (rest.nonEmpty && problem.isEmpty) {
      rest match {
        case "--why" :: value :: tail =>
          why = Some(value); rest = tail
        case "--why" :: Nil =>
          problem = Some("--why needs a value"); rest = Nil
        case "--verification" :: value :: tail =>
          verification = verification :+ value; rest = tail
        case "--verification" :: Nil =>
          problem = Some("--verification needs a value"); rest = Nil
        case flag :: _ if flag.startsWith("--") =>
          problem = Some(s"unknown flag: $flag"); rest = Nil
        case word :: tail =>
          words = words :+ word; rest = tail
        case Nil =>
          rest = Nil
      }
    }

    problem.toLeft(words.mkString(" ")).flatMap { title =>
      if (title.isEmpty) Left("a title is required")
      else Right((title, why, verification))
    }
  }

  private def usage(stream: PrintStream): Unit = {
    stream.println("rocket - planning and throughput for a career")
    stream.println()
    stream.println("Planning Mode")
    stream.println("  rocket career add <title> [--why <text>] [--verification <text>]...")
    stream.println("  rocket career list [--archived]")
    stream.println("  rocket career archive <id> | unarchive <id>")
    stream.println()
    stream.println("Only `career` is implemented in this first slice.")
  }
}
