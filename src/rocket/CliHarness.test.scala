package rocket

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.file.{Files, Path}

/** A throwaway store per test, plus the ways a test observes it. */
abstract class CliHarness extends munit.FunSuite {

  protected def tempDb(): Path =
    Files.createTempDirectory("rocket-test").resolve("rocket.db")

  protected def run(db: Path, args: String*): (Int, String, String) = {
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val cli = new Cli(
      db,
      new PrintStream(out, true, "UTF-8"),
      new PrintStream(err, true, "UTF-8")
    )
    (cli.run(args.toList), out.toString("UTF-8"), err.toString("UTF-8"))
  }

  /** Reads the store directly, for facts no read command exposes yet. */
  protected def rows(db: Path, sql: String): List[List[String]] = {
    val connection = Db.connect(db)
    try {
      val statement = connection.createStatement()
      try {
        val rs = statement.executeQuery(sql)
        val columns = rs.getMetaData.getColumnCount
        val buffer = List.newBuilder[List[String]]
        while (rs.next())
          buffer += (1 to columns).map(index => String.valueOf(rs.getString(index))).toList
        buffer.result()
      } finally statement.close()
    } finally connection.close()
  }

  protected def execute(db: Path, sql: String): Unit = {
    val connection = Db.connect(db)
    try {
      val statement = connection.createStatement()
      try statement.execute(sql)
      finally statement.close()
    } finally connection.close()
  }
}
