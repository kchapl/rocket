package rocket

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.file.{Files, Path}
import java.time.LocalDate

class CareerCliTest extends munit.FunSuite {

  private def tempDb(): Path =
    Files.createTempDirectory("rocket-test").resolve("rocket.db")

  private def run(db: Path, args: String*): (Int, String, String) = {
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val cli = new Cli(
      db,
      new PrintStream(out, true, "UTF-8"),
      new PrintStream(err, true, "UTF-8")
    )
    (cli.run(args.toList), out.toString("UTF-8"), err.toString("UTF-8"))
  }

  test("a Career Goal added by one invocation is listed by the next") {
    val db = tempDb()
    val (addCode, addOut, _) = run(db, "career", "add", "become a staff engineer")

    assertEquals(addCode, 0)
    assertEquals(addOut.trim, "g1")

    val (listCode, listOut, _) = run(db, "career", "list")

    assertEquals(listCode, 0)
    assert(listOut.contains("g1"), listOut)
    assert(listOut.contains("become a staff engineer"), listOut)
  }

  test("ids continue above the highest already present") {
    val db = tempDb()
    run(db, "career", "add", "first")
    val (_, secondOut, _) = run(db, "career", "add", "second")

    assertEquals(secondOut.trim, "g2")
  }

  test("why and verification are kept, and verification keeps the order given") {
    val db = tempDb()
    run(
      db,
      "career",
      "add",
      "become a staff engineer",
      "--why",
      "to choose my own work",
      "--verification",
      "a design review ships without me",
      "--verification",
      "someone outside my team cites the doc"
    )

    val (_, listOut, _) = run(db, "career", "list")

    assert(listOut.contains("why: to choose my own work"), listOut)
    assert(
      listOut.contains(
        "verification: a design review ships without me; someone outside my team cites the doc"
      ),
      listOut
    )
  }

  test("archiving hides a Goal from the default list but not from --archived") {
    val db = tempDb()
    run(db, "career", "add", "become a staff engineer")

    val (archiveCode, archiveOut, _) = run(db, "career", "archive", "g1")
    assertEquals(archiveCode, 0)
    assertEquals(archiveOut.trim, "archived g1")

    val (_, activeOut, _) = run(db, "career", "list")
    assert(!activeOut.contains("become a staff engineer"), activeOut)

    val (_, archivedOut, _) = run(db, "career", "list", "--archived")
    assert(archivedOut.contains("become a staff engineer"), archivedOut)
    assert(archivedOut.contains("(archived)"), archivedOut)

    val (_, unarchiveOut, _) = run(db, "career", "unarchive", "g1")
    assertEquals(unarchiveOut.trim, "unarchived g1")

    val (_, backOut, _) = run(db, "career", "list")
    assert(backOut.contains("become a staff engineer"), backOut)
  }

  test("archiving an unknown Goal reports a domain error, not a crash") {
    val db = tempDb()
    val (code, out, err) = run(db, "career", "archive", "g9")

    assertEquals(code, 1)
    assertEquals(out, "")
    assert(err.contains("no Career Goal g9"), err)
  }

  test("a missing title is a usage error") {
    val db = tempDb()
    val (code, _, err) = run(db, "career", "add")

    assertEquals(code, 2)
    assert(err.contains("a title is required"), err)
  }

  test("an empty store says so rather than printing nothing") {
    val (code, out, _) = run(tempDb(), "career", "list")

    assertEquals(code, 0)
    assertEquals(out.trim, "no Career Goals")
  }

  test("no arguments prints usage and succeeds") {
    val (code, out, _) = run(tempDb())

    assertEquals(code, 0)
    assert(out.contains("rocket career add"), out)
  }

  test("an ambiguous prefix is an error rather than a guess") {
    val db = tempDb()
    run(db, "career", "add", "first")
    run(db, "career", "add", "second")

    val (code, _, err) = run(db, "career", "archive", "g")

    assertEquals(code, 1)
    assert(err.contains("matches g1, g2"), err)
  }

  test("a title prefix resolves when it is the only match") {
    val db = tempDb()
    run(db, "career", "add", "become a staff engineer")
    run(db, "career", "add", "run a marathon")

    val (code, out, _) = run(db, "career", "archive", "become")

    assertEquals(code, 0)
    assertEquals(out.trim, "archived g1")
  }

  test("Quarter.of reads the calendar quarter") {
    assertEquals(Quarter.of(LocalDate.of(2026, 1, 1)), "2026-Q1")
    assertEquals(Quarter.of(LocalDate.of(2026, 8, 1)), "2026-Q3")
    assertEquals(Quarter.of(LocalDate.of(2026, 12, 31)), "2026-Q4")
    assertEquals(Quarter.of(LocalDate.of(2027, 1, 1)), "2027-Q1")
  }
}
