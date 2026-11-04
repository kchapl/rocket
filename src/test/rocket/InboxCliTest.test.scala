package rocket

class InboxCliTest extends CliHarness {

  test("a captured Idea keeps the text given and takes the next id") {
    val db = tempDb()

    val (firstCode, firstOut, _) = run(db, "inbox", "add", "become a staff engineer")
    val (_, secondOut, _) = run(db, "inbox", "add", "write a book about it")

    assertEquals(firstCode, 0)
    assertEquals(firstOut.trim, "idea1")
    assertEquals(secondOut.trim, "idea2")
    assertEquals(
      run(db, "inbox", "list")._2.trim.linesIterator.toList,
      List("idea1  become a staff engineer", "idea2  write a book about it")
    )
  }

  test("an Idea belongs to no Quarter and to nothing else") {
    val db = tempDb()
    run(db, "inbox", "add", "an unbounded thought")

    assertEquals(
      rows(db, "SELECT disposition, promoted_to, captured_at IS NOT NULL FROM idea"),
      List(List("pending", "null", "1"))
    )
    assertEquals(rows(db, "SELECT COUNT(*) FROM outcome"), List(List("0")))
    assertEquals(rows(db, "SELECT COUNT(*) FROM objective"), List(List("0")))
  }

  test("promoting to a Goal creates it with the Idea's text and records what it became") {
    val db = tempDb()
    run(db, "inbox", "add", "become a staff engineer")

    val (code, out, _) = run(db, "inbox", "promote", "idea1", "--to-goal")

    assertEquals(code, 0)
    assertEquals(out.trim, "g1")
    assertEquals(rows(db, "SELECT title FROM goal"), List(List("become a staff engineer")))
    assertEquals(
      rows(db, "SELECT disposition, promoted_to FROM idea WHERE id = 'idea1'"),
      List(List("promoted", "g1"))
    )
  }

  test("promoting to an Objective commits it to the current Quarter under the Goal given") {
    val db = tempDb()
    run(db, "career", "add", "become a staff engineer")
    run(db, "inbox", "add", "ship the migration")

    val (code, out, _) = run(db, "inbox", "promote", "idea1", "--to-obj", "g1")

    assertEquals(code, 0)
    assertEquals(out.trim, "obj1")
    assertEquals(
      rows(db, "SELECT title, goal_id, quarter FROM objective"),
      List(List("ship the migration", "g1", Quarter.current))
    )
    assertEquals(rows(db, "SELECT promoted_to FROM idea WHERE id = 'idea1'"), List(List("obj1")))
  }

  test("promoting to an Outcome yields a parentless, unflagged Outcome") {
    val db = tempDb()
    run(db, "inbox", "add", "capture the team's oncall pain")

    val (code, out, _) = run(db, "inbox", "promote", "idea1", "--to-out")

    assertEquals(code, 0)
    assertEquals(out.trim, "out1")
    assertEquals(
      rows(db, "SELECT objective_id, emergency, why FROM outcome WHERE id = 'out1'"),
      List(List("null", "0", "null"))
    )
    assertEquals(run(db, "backlog")._2.trim, "1  out1  capture the team's oncall pain")
    assertEquals(run(db, "quarter", "review")._2.contains("Parentless Outcomes"), true)
  }

  test("a promoted Idea leaves the inbox but stays in the record") {
    val db = tempDb()
    run(db, "inbox", "add", "become a staff engineer")
    run(db, "inbox", "promote", "idea1", "--to-goal")

    assertEquals(run(db, "inbox", "list")._2.trim, "no pending Ideas")
    assertEquals(
      run(db, "inbox", "list", "--all")._2.trim,
      "idea1  become a staff engineer  (promoted to g1)"
    )
  }

  test("a discarded Idea is kept as the record of the decision") {
    val db = tempDb()
    run(db, "inbox", "add", "learn to juggle")

    val (code, out, _) = run(db, "inbox", "discard", "idea1")

    assertEquals(code, 0)
    assertEquals(out.trim, "idea1 discarded")
    assertEquals(run(db, "inbox", "list")._2.trim, "no pending Ideas")
    assertEquals(run(db, "inbox", "list", "--all")._2.trim, "idea1  learn to juggle  (discarded)")
  }

  test("a verdict is final: a discarded Idea cannot be promoted or discarded again") {
    val db = tempDb()
    run(db, "inbox", "add", "learn to juggle")
    run(db, "inbox", "discard", "idea1")

    val (promoteCode, _, promoteErr) = run(db, "inbox", "promote", "idea1", "--to-goal")
    assertEquals(promoteCode, 1)
    assert(promoteErr.contains("idea1 is already discarded"), promoteErr)

    val (discardCode, _, discardErr) = run(db, "inbox", "discard", "idea1")
    assertEquals(discardCode, 1)
    assert(discardErr.contains("idea1 is already discarded"), discardErr)
    assertEquals(rows(db, "SELECT COUNT(*) FROM goal"), List(List("0")))
  }

  test("a promotion that fails leaves the Idea pending and creates nothing") {
    val db = tempDb()
    run(db, "inbox", "add", "ship the migration")

    val (code, _, err) = run(db, "inbox", "promote", "idea1", "--to-obj", "g9")

    assertEquals(code, 1)
    assert(err.contains("no Career Goal g9"), err)
    assertEquals(
      rows(db, "SELECT disposition, promoted_to FROM idea WHERE id = 'idea1'"),
      List(List("pending", "null"))
    )
    assertEquals(rows(db, "SELECT COUNT(*) FROM objective"), List(List("0")))
  }

  test("promoting needs exactly one target") {
    val db = tempDb()
    run(db, "inbox", "add", "an idea")

    val (noneCode, _, noneErr) = run(db, "inbox", "promote", "idea1")
    assertEquals(noneCode, 2)
    assert(noneErr.contains("--to-goal"), noneErr)

    val (twoCode, _, twoErr) =
      run(db, "inbox", "promote", "idea1", "--to-goal", "--to-out")
    assertEquals(twoCode, 2)
    assert(twoErr.contains("one Idea one way"), twoErr)

    val (goalCode, _, goalErr) = run(db, "inbox", "promote", "idea1", "--to-obj")
    assertEquals(goalCode, 2)
    assert(goalErr.contains("--to-obj needs a Career Goal"), goalErr)
  }

  test("an Idea is found by id or by a unique prefix of its text") {
    val db = tempDb()
    run(db, "inbox", "add", "become a staff engineer")
    run(db, "inbox", "add", "learn to juggle")

    val (code, out, _) = run(db, "inbox", "discard", "learn")

    assertEquals(code, 0)
    assertEquals(out.trim, "idea2 discarded")
  }

  test("inbox reports an unknown Idea and empty text as errors") {
    val db = tempDb()

    val (unknownCode, _, unknownErr) = run(db, "inbox", "discard", "idea9")
    assertEquals(unknownCode, 1)
    assert(unknownErr.contains("no Idea idea9"), unknownErr)

    val (emptyCode, _, emptyErr) = run(db, "inbox", "add")
    assertEquals(emptyCode, 2)
    assert(emptyErr.contains("an Idea needs text"), emptyErr)

    assertEquals(run(db, "inbox", "list")._2.trim, "no pending Ideas")
  }
}
