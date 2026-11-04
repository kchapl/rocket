package rocket

import java.nio.file.{Files, Path}

/** `export` and `import`: one rocket document carries the whole record between stores. */
class PortabilityCliTest extends CliHarness {

  /** One of everything, so a round trip has to carry every kind of record. */
  private def populated(): Path = {
    val db = tempDb()
    run(
      db,
      "career",
      "add",
      "I set my own priorities",
      "--why",
      "to choose the work",
      "--verification",
      "nobody reassigns my tickets"
    )
    run(db, "career", "add", "I finish what I start")
    run(db, "career", "archive", "g2")
    run(
      db,
      "objective",
      "add",
      "releases need no human",
      "--goal",
      "g1",
      "--why",
      "so I stop being the release",
      "--verification",
      "the release job is green"
    )
    run(
      db,
      "outcome",
      "add",
      "--objective",
      "obj1",
      "the migration runs on the new scheduler",
      "--why",
      "so releases stop waiting",
      "--verification",
      "the scheduler cuts the release"
    )
    run(db, "progress", "start", "out1")
    run(db, "progress", "box", "out1")
    run(db, "progress", "box", "out1")
    run(db, "progress", "achieve", "out1")
    run(db, "outcome", "add", "audit the CI runners")
    run(db, "outcome", "add", "--emergency", "prod is down")
    run(db, "progress", "drop", "out3")
    run(db, "inbox", "add", "read the SRE book")
    run(db, "inbox", "promote", "idea1", "--to-out")
    run(db, "inbox", "add", "learn Rust")
    run(db, "inbox", "discard", "idea2")
    db
  }

  private def runtimeFile(name: String): Path =
    Files.createTempDirectory("rocket-portability").resolve(name)

  test("export writes the whole record as one rocket document") {
    val (code, out, err) = run(populated(), "export")

    assertEquals(code, 0)
    assertEquals(err, "")
    assertEquals(out.linesIterator.size, 1)
    assert(out.trim.startsWith("""{"format":"rocket","schemaVersion":1,"""), out)
    assert(out.contains(s""""carriedThrough":"${Quarter.current}""""), out)
    List("\"goals\":[", "\"objectives\":[", "\"outcomes\":[", "\"timeBoxes\":[", "\"ideas\":[")
      .foreach(key => assert(out.contains(key), s"$key missing from $out"))
  }

  test("export --output writes the document to a file and names it") {
    val file = runtimeFile("record.json")
    val db = populated()
    val expected = run(db, "export")._2.trim

    val (code, out, err) = run(db, "export", "--output", file.toString)

    assertEquals(code, 0)
    assertEquals(err, "")
    assertEquals(out.trim, s"exported to $file")
    assertEquals(Files.readString(file).trim, expected)
  }

  test("export without an output file is a usage error") {
    val (code, out, err) = run(tempDb(), "export", "--output")

    assertEquals(code, 2)
    assertEquals(out, "")
    assert(err.contains("--output needs a file"), err)
  }

  test("a document imported into an empty store exports identically") {
    val source = populated()
    val file = runtimeFile("record.json")
    run(source, "export", "--output", file.toString)

    val target = tempDb()
    val (code, out, err) = run(target, "import", file.toString)

    assertEquals(code, 0)
    assertEquals(err, "")
    assert(out.trim.startsWith("imported "), out)
    assert(out.trim.endsWith(s"from $file"), out)
    assertEquals(run(target, "export")._2, run(source, "export")._2)
  }

  test("import refuses a store that already holds data unless --force") {
    val db = populated()
    val file = runtimeFile("record.json")
    run(db, "export", "--output", file.toString)
    val before = run(db, "export")._2

    val (code, out, err) = run(db, "import", file.toString)

    assertEquals(code, 1)
    assertEquals(out, "")
    assert(err.contains("--force replaces it"), err)
    assertEquals(run(db, "export")._2, before)
  }

  test("import --force replaces what the store held, ids included") {
    val db = populated()
    val other = tempDb()
    run(other, "career", "add", "I write every day")
    val file = runtimeFile("other.json")
    run(other, "export", "--output", file.toString)

    val (code, out, err) = run(db, "import", file.toString, "--force")

    assertEquals(code, 0)
    assertEquals(err, "")
    assert(out.trim.startsWith("imported 1 record from"), out)
    assertEquals(run(db, "career", "list")._2.trim, "g1  I write every day")
    assertEquals(run(db, "backlog")._2.trim, s"no open Outcomes in ${Quarter.current}")
  }

  test("import refuses an emergency Outcome that states Verification") {
    val db = tempDb()
    val file = runtimeFile("emergency-verification.json")
    Files.writeString(
      file,
      s"""{"format":"rocket","schemaVersion":1,"carriedThrough":"${Quarter.current}",
         |"goals":[],"objectives":[],"timeBoxes":[],"ideas":[],
         |"outcomes":[{"id":"out1","title":"prod is down","quarter":"${Quarter.current}",
         |"objectiveId":null,"emergency":true,"why":null,"verification":["the pager stops"],
         |"rank":1,"status":"open","stage":"todo"}]}""".stripMargin
    )

    val (code, out, err) = run(db, "import", file.toString)

    assertEquals(code, 1)
    assertEquals(out, "")
    assert(err.contains("carries no Verification"), err)
    assertEquals(rows(db, "SELECT id FROM outcome"), Nil)
  }

  test("import says why a file is not a rocket document") {
    val db = tempDb()

    val broken = runtimeFile("broken.json")
    Files.writeString(broken, """{"format": """)
    val (brokenCode, _, brokenErr) = run(db, "import", broken.toString)
    assertEquals(brokenCode, 1)
    assert(brokenErr.contains("not JSON:"), brokenErr)

    val missing = broken.resolveSibling("missing.json")
    val (missingCode, _, missingErr) = run(db, "import", missing.toString)
    assertEquals(missingCode, 1)
    assert(missingErr.contains("cannot read"), missingErr)

    val foreign = broken.resolveSibling("foreign.json")
    Files.writeString(foreign, """{"format":"trajectory","schemaVersion":1}""")
    val (foreignCode, _, foreignErr) = run(db, "import", foreign.toString)
    assertEquals(foreignCode, 1)
    assert(foreignErr.contains("not a rocket document"), foreignErr)

    val future = broken.resolveSibling("future.json")
    Files.writeString(future, """{"format":"rocket","schemaVersion":2}""")
    val (futureCode, _, futureErr) = run(db, "import", future.toString)
    assertEquals(futureCode, 1)
    assert(futureErr.contains("unsupported schemaVersion 2"), futureErr)
  }

  test("import restores the Quarter the store was current through") {
    val source = populated()
    val stale = back(1)
    val json = run(source, "export")._2.trim
    val crafted = json.replace(
      s""""carriedThrough":"${Quarter.current}"""",
      s""""carriedThrough":"$stale""""
    )
    val file = runtimeFile("stale.json")
    Files.writeString(file, crafted)

    val target = tempDb()
    assertEquals(run(target, "import", file.toString)._1, 0)

    assertEquals(
      rows(target, "SELECT value FROM setting WHERE key = 'carried_through'"),
      List(List(stale))
    )
  }

  test("import takes one file and reads it, or says how to call it") {
    val (code, out, err) = run(tempDb(), "import")

    assertEquals(code, 2)
    assertEquals(out, "")
    assert(err.contains("rocket import <file.json> [--force]"), err)
  }

  test("export and import answer --help like every other noun") {
    List("export", "import").foreach { noun =>
      val (code, out, err) = run(tempDb(), noun, "--help")

      assertEquals(code, 0, noun)
      assertEquals(err, "", noun)
      assert(out.startsWith(s"rocket $noun - "), out)
    }
  }
}
