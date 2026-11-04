package rocket

/** `--json` on the read commands: one compact line carrying the records the text shows. */
class JsonCliTest extends CliHarness {

  test("career list --json is an array of Goals, and an empty listing is []") {
    val db = tempDb()

    assertEquals(run(db, "career", "list", "--json")._2.trim, "[]")

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
    val (code, out, err) = run(db, "career", "list", "--json")

    assertEquals(code, 0)
    assertEquals(err, "")
    assertEquals(
      out.trim,
      """[{"id":"g1","title":"I set my own priorities","why":"to choose the work","verification":["nobody reassigns my tickets"],"archived":false}]"""
    )
  }

  test("an absent fact is null, and an archived Goal says so") {
    val db = tempDb()
    run(db, "career", "add", "I set my own priorities")
    run(db, "career", "archive", "g1")

    val (_, out, _) = run(db, "career", "list", "--archived", "--json")

    assertEquals(
      out.trim,
      """[{"id":"g1","title":"I set my own priorities","why":null,"verification":[],"archived":true}]"""
    )
  }

  test("quarter --json is a string, and quarter list --json an array of names") {
    val db = tempDb()

    assertEquals(run(db, "quarter", "--json")._2.trim, "\"" + Quarter.current + "\"")
    assertEquals(run(db, "quarter", "list", "--json")._2.trim, "[]")

    run(db, "outcome", "add", "the migration runs on the new scheduler")

    assertEquals(run(db, "quarter", "list", "--json")._2.trim, s"""["${Quarter.current}"]""")
  }

  test("quarter review --json names all three Gap kinds, empty ones included") {
    val db = tempDb()
    run(db, "career", "add", "I set my own priorities")

    val (code, out, err) = run(db, "quarter", "review", "--json")

    assertEquals(code, 0)
    assertEquals(err, "")
    assert(out.startsWith(s"""{"quarter":"${Quarter.current}","""), out)
    assert(out.contains("\"unalignedGoals\":[{\"id\":\"g1\""), out)
    assert(out.contains("\"emptyObjectives\":[]"), out)
    assert(out.contains("\"parentlessOutcomes\":[]"), out)
  }

  test("backlog --json carries rank, status, stage and the emergency flag") {
    val db = tempDb()
    run(db, "outcome", "add", "prod is down", "--emergency")
    run(db, "outcome", "add", "the migration runs on the new scheduler")
    run(db, "progress", "start", "out2")

    val (code, out, err) = run(db, "backlog", "--json")

    assertEquals(code, 0)
    assertEquals(err, "")
    assertEquals(
      out.trim,
      s"""[{"id":"out1","title":"prod is down","quarter":"${Quarter.current}","objectiveId":null,"emergency":true,"why":null,"verification":[],"rank":1,"status":"open","stage":"todo"},{"id":"out2","title":"the migration runs on the new scheduler","quarter":"${Quarter.current}","objectiveId":null,"emergency":false,"why":null,"verification":[],"rank":2,"status":"open","stage":"doing"}]"""
    )
  }

  test("inbox list --json shows the disposition and what an Idea became") {
    val db = tempDb()
    run(db, "inbox", "add", "read the SRE book")

    val pending = run(db, "inbox", "list", "--json")._2
    assert(pending.contains("\"disposition\":\"pending\""), pending)
    assert(pending.contains("\"promotedTo\":null"), pending)

    run(db, "inbox", "promote", "idea1", "--to-goal")
    val promoted = run(db, "inbox", "list", "--all", "--json")._2

    assert(promoted.contains("\"disposition\":\"promoted\""), promoted)
    assert(promoted.contains("\"promotedTo\":\"g1\""), promoted)
  }

  test("trace --json nests the lineage and nulls what the Outcome lacks") {
    val db = tempDb()
    run(db, "career", "add", "I set my own priorities")
    run(db, "objective", "add", "releases need no human", "--goal", "g1")
    run(db, "outcome", "add", "--objective", "obj1", "the migration runs on the new scheduler")
    run(db, "outcome", "add", "audit the CI runners")

    val filed = run(db, "trace", "--json", "out1")._2.trim

    assert(filed.startsWith("""{"goal":{"id":"g1","""), filed)
    assert(filed.contains("\"goalId\":\"g1\""), filed)
    assert(filed.contains("\"objectiveId\":\"obj1\""), filed)

    val parentless = run(db, "trace", "out2", "--json")._2.trim

    assert(parentless.startsWith("""{"goal":null,"objective":null,"outcome":{"id":"out2""""), parentless)
    assert(parentless.contains("\"objectiveId\":null"), parentless)
  }

  test("JSON escapes what a title can hold, so the answer stays one line") {
    val db = tempDb()
    run(db, "career", "add", "say \"hi\" and \\ bye", "with", "a\nbreak")

    val (_, out, _) = run(db, "career", "list", "--json")

    assertEquals(out.linesIterator.size, 1)
    assert(out.contains("""say \"hi\" and \\ bye"""), out)
    assert(out.contains("""a\nbreak"""), out)
  }

  test("a rollover notice moves to stderr, leaving stdout as one JSON value") {
    val db = tempDb()
    run(db, "quarter")
    execute(db, s"UPDATE setting SET value = '${back(1)}' WHERE key = 'carried_through'")

    val (code, out, err) = run(db, "backlog", "--json")

    assertEquals(code, 0)
    assertEquals(out.trim, "[]")
    assert(err.startsWith("Notice: "), err)
    assert(err.contains(s"into ${Quarter.current}"), err)
  }

  test("--json belongs to the read commands, so a write refuses it") {
    val (code, _, err) = run(tempDb(), "career", "add", "a title", "--json")

    assertEquals(code, 2)
    assert(err.contains("unknown flag: --json"), err)
  }
}
