package rocket

class ReviewCliTest extends CliHarness {

  // --- objective drop -----------------------------------------------------

  test("dropping an Objective drops every open Outcome under it and nothing else") {
    val db = tempDb()
    run(db, "career", "add", "become a staff engineer")
    run(db, "objective", "add", "ship the migration", "--goal", "g1")
    run(db, "objective", "add", "collect notes", "--goal", "g1")
    run(db, "outcome", "add", "--objective", "obj1", "first")
    run(db, "outcome", "add", "--objective", "obj1", "second")
    run(db, "outcome", "add", "--objective", "obj2", "third")
    run(db, "progress", "box", "out1")

    val (code, out, _) = run(db, "objective", "drop", "obj1")

    assertEquals(code, 0)
    assertEquals(out.trim, "obj1 dropped 2 outcomes")
    assertEquals(
      rows(db, "SELECT id, status FROM outcome ORDER BY id"),
      List(List("out1", "dropped"), List("out2", "dropped"), List("out3", "open"))
    )
    assertEquals(rows(db, "SELECT COUNT(*) FROM time_box"), List(List("1")))
    assertEquals(run(db, "backlog")._2.trim.linesIterator.toList, List("3  out3  third"))
  }

  test("dropping an Objective with no open work is reported and changes nothing") {
    val db = tempDb()
    run(db, "career", "add", "become a staff engineer")
    run(db, "objective", "add", "collect notes", "--goal", "g1")

    val (code, out, _) = run(db, "objective", "drop", "obj1")

    assertEquals(code, 0)
    assertEquals(out.trim, "obj1 dropped 0 outcomes")
    assertEquals(rows(db, "SELECT COUNT(*) FROM objective"), List(List("1")))
  }

  test("dropping an unknown Objective is a domain error") {
    val (code, _, err) = run(tempDb(), "objective", "drop", "obj9")

    assertEquals(code, 1)
    assert(err.contains("no Quarterly Objective obj9"), err)
  }

  // --- outcome parent -----------------------------------------------------

  test("parenting an unfiled Outcome closes the Parentless Outcome gap") {
    val db = tempDb()
    run(db, "career", "add", "become a staff engineer")
    run(db, "objective", "add", "ship the migration", "--goal", "g1")
    run(db, "outcome", "add", "unfiled thought")
    assert(run(db, "quarter", "review")._2.contains("Parentless Outcomes"))

    val (code, out, _) = run(db, "outcome", "parent", "out1", "obj1")

    assertEquals(code, 0)
    assertEquals(out.trim, "out1 parented to obj1")
    assertEquals(
      rows(db, "SELECT objective_id, emergency FROM outcome WHERE id = 'out1'"),
      List(List("obj1", "0"))
    )
    assertEquals(run(db, "quarter", "review")._2.trim, s"${Quarter.current}: no Gaps")
  }

  test("parenting an emergency Outcome clears the flag and leaves its empty Why alone") {
    val db = tempDb()
    run(db, "career", "add", "become a staff engineer")
    run(db, "objective", "add", "ship the migration", "--goal", "g1")
    run(db, "outcome", "add", "--emergency", "fix production")

    val (code, _, _) = run(db, "outcome", "parent", "out1", "obj1")

    assertEquals(code, 0)
    assertEquals(
      rows(db, "SELECT objective_id, emergency, why FROM outcome WHERE id = 'out1'"),
      List(List("obj1", "0", "null"))
    )
  }

  test("re-parenting replaces the Objective and keeps no record of the previous one") {
    val db = tempDb()
    run(db, "career", "add", "become a staff engineer")
    run(db, "objective", "add", "first objective", "--goal", "g1")
    run(db, "objective", "add", "second objective", "--goal", "g1")
    run(db, "outcome", "add", "--objective", "obj1", "work")

    val (code, _, _) = run(db, "outcome", "parent", "out1", "obj2")

    assertEquals(code, 0)
    assertEquals(rows(db, "SELECT objective_id FROM outcome WHERE id = 'out1'"), List(List("obj2")))
  }

  test("an Objective from another Quarter cannot parent an Outcome") {
    val db = tempDb()
    run(db, "career", "add", "become a staff engineer")
    execute(
      db,
      "INSERT INTO objective(id, title, goal_id, quarter, why) VALUES ('obj1', 'old', 'g1', '2025-Q1', NULL)"
    )
    run(db, "outcome", "add", "work")

    val (code, _, err) = run(db, "outcome", "parent", "out1", "obj1")

    assertEquals(code, 1)
    assert(err.contains("committed in 2025-Q1"), err)
    assertEquals(rows(db, "SELECT objective_id FROM outcome WHERE id = 'out1'"), List(List("null")))
  }

  test("parenting reports an unknown Outcome or Objective") {
    val db = tempDb()
    run(db, "career", "add", "become a staff engineer")
    run(db, "objective", "add", "ship the migration", "--goal", "g1")

    val (outcomeCode, _, outcomeErr) = run(db, "outcome", "parent", "out9", "obj1")
    assertEquals(outcomeCode, 1)
    assert(outcomeErr.contains("no Outcome out9"), outcomeErr)

    run(db, "outcome", "add", "work")
    val (objectiveCode, _, objectiveErr) = run(db, "outcome", "parent", "out1", "obj9")
    assertEquals(objectiveCode, 1)
    assert(objectiveErr.contains("no Quarterly Objective obj9"), objectiveErr)
  }

  // --- quarter review -----------------------------------------------------

  test("review reports every kind of Gap in one pass") {
    val db = tempDb()
    run(db, "career", "add", "become a staff engineer")
    run(db, "career", "add", "run a marathon")
    run(db, "objective", "add", "ship the migration", "--goal", "g1")
    run(db, "objective", "add", "collect notes", "--goal", "g1")
    run(db, "outcome", "add", "--objective", "obj1", "parented work")
    run(db, "outcome", "add", "unfiled thought")
    run(db, "outcome", "add", "--emergency", "fix production")

    val (code, out, _) = run(db, "quarter", "review")

    assertEquals(code, 0)
    assertEquals(
      out.trim.linesIterator.toList,
      List(
        Quarter.current,
        "  Unaligned Goals",
        "    g2  run a marathon",
        "  Empty Objectives",
        "    obj2  collect notes  (g1)",
        "  Parentless Outcomes",
        "    out2  unfiled thought"
      )
    )
  }

  test("an archived Goal and an emergency Outcome are never Gaps") {
    val db = tempDb()
    run(db, "career", "add", "run a marathon")
    run(db, "career", "add", "become a staff engineer")
    run(db, "career", "archive", "g1")
    run(db, "objective", "add", "ship the migration", "--goal", "g2")
    run(db, "outcome", "add", "--objective", "obj1", "parented work")
    run(db, "outcome", "add", "--emergency", "fix production")

    val (code, out, _) = run(db, "quarter", "review")

    assertEquals(code, 0)
    assertEquals(out.trim, s"${Quarter.current}: no Gaps")
  }

  test("review of a named Quarter reports that Quarter") {
    val db = tempDb()
    run(db, "career", "add", "become a staff engineer")
    run(db, "career", "add", "run a marathon")
    run(db, "objective", "add", "current commitment", "--goal", "g1")
    run(db, "objective", "add", "marathon plan", "--goal", "g2")
    run(db, "outcome", "add", "--objective", "obj1", "current work")
    run(db, "outcome", "add", "--objective", "obj2", "marathon work")
    execute(
      db,
      "INSERT INTO objective(id, title, goal_id, quarter, why) VALUES ('obj3', 'old commitment', 'g1', '2025-Q1', NULL)"
    )
    execute(
      db,
      """INSERT INTO outcome(id, title, quarter, objective_id, emergency, why, rank, status, stage)
        |VALUES ('out3', 'old loose work', '2025-Q1', NULL, 0, NULL, 1, 'dropped', 'todo')""".stripMargin
    )

    val (code, out, _) = run(db, "quarter", "review", "2025-Q1")

    assertEquals(code, 0)
    assertEquals(
      out.trim.linesIterator.toList,
      List(
        "2025-Q1",
        "  Unaligned Goals",
        "    g2  run a marathon",
        "  Empty Objectives",
        "    obj3  old commitment  (g1)",
        "  Parentless Outcomes",
        "    out3  old loose work"
      )
    )
    assertEquals(run(db, "quarter", "review")._2.trim, s"${Quarter.current}: no Gaps")
  }

  test("an unparseable Quarter is a usage error") {
    val (code, _, err) = run(tempDb(), "quarter", "review", "2026-Q5")

    assertEquals(code, 2)
    assert(err.contains("not a Quarter: 2026-Q5"), err)
  }
}
