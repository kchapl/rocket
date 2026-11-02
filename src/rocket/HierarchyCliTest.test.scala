package rocket

class HierarchyCliTest extends CliHarness {

  // --- Quarter ------------------------------------------------------------

  test("quarter prints the current Quarter, and an empty record lists none") {
    val db = tempDb()

    assertEquals(run(db, "quarter")._2.trim, Quarter.current)
    assertEquals(run(db, "quarter", "current")._2.trim, Quarter.current)
    assertEquals(run(db, "quarter", "list")._2.trim, "no Quarters in the record")
  }

  test("a Quarter enters the record once an Outcome is stamped with it") {
    val db = tempDb()
    run(db, "outcome", "add", "write the doc")

    assertEquals(run(db, "quarter", "list")._2.trim, Quarter.current)
  }

  // --- Quarterly Objectives -----------------------------------------------

  test("an Objective is committed to the current Quarter under a Goal") {
    val db = tempDb()
    run(db, "career", "add", "become a staff engineer")

    val (code, out, _) = run(
      db,
      "objective",
      "add",
      "ship the migration",
      "--goal",
      "g1",
      "--why",
      "the old path blocks every release",
      "--verification",
      "the old path is gone",
      "--verification",
      "releases need no manual step"
    )

    assertEquals(code, 0)
    assertEquals(out.trim, "obj1")
    assertEquals(
      rows(db, "SELECT goal_id, quarter, why FROM objective WHERE id = 'obj1'"),
      List(List("g1", Quarter.current, "the old path blocks every release"))
    )
    assertEquals(
      rows(
        db,
        "SELECT statement FROM objective_verification WHERE objective_id = 'obj1' ORDER BY ordinal"
      ),
      List(List("the old path is gone"), List("releases need no manual step"))
    )
  }

  test("Objective ids continue above the highest already present") {
    val db = tempDb()
    run(db, "career", "add", "become a staff engineer")

    run(db, "objective", "add", "first", "--goal", "g1")
    val (_, secondOut, _) = run(db, "objective", "add", "second", "--goal", "g1")

    assertEquals(secondOut.trim, "obj2")
  }

  test("an Objective without --goal is a usage error") {
    val db = tempDb()
    run(db, "career", "add", "become a staff engineer")

    val (code, out, err) = run(db, "objective", "add", "ship the migration")

    assertEquals(code, 2)
    assertEquals(out, "")
    assert(err.contains("an Objective needs --goal <id>"), err)
  }

  test("an Objective under an unknown Goal reports a domain error") {
    val (code, _, err) = run(tempDb(), "objective", "add", "ship the migration", "--goal", "g9")

    assertEquals(code, 1)
    assert(err.contains("no Career Goal g9"), err)
  }

  test("an archived Goal takes no new Objective") {
    val db = tempDb()
    run(db, "career", "add", "run a marathon")
    run(db, "career", "archive", "g1")

    val (code, _, err) = run(db, "objective", "add", "marathon in spring", "--goal", "g1")

    assertEquals(code, 1)
    assert(err.contains("Career Goal g1 is archived"), err)
  }

  // --- Outcomes -----------------------------------------------------------

  test("an unfiled Outcome is parentless, unflagged and ranks first") {
    val db = tempDb()

    val (code, out, _) = run(
      db,
      "outcome",
      "add",
      "write the design doc",
      "--why",
      "so review has something to read"
    )

    assertEquals(code, 0)
    assertEquals(out.trim, "out1")
    assertEquals(
      rows(
        db,
        """SELECT quarter, objective_id, emergency, why, rank, status, stage
          |FROM outcome WHERE id = 'out1'""".stripMargin
      ),
      List(List(Quarter.current, "null", "0", "so review has something to read", "1", "open", "todo"))
    )
  }

  test("Outcomes queue up in the order they were planned") {
    val db = tempDb()
    run(db, "outcome", "add", "first")
    run(db, "outcome", "add", "second")

    assertEquals(
      rows(db, "SELECT id, rank FROM outcome ORDER BY rank"),
      List(List("out1", "1"), List("out2", "2"))
    )
    assertEquals(
      run(db, "backlog")._2.trim.linesIterator.toList,
      List("1  out1  first", "2  out2  second")
    )
  }

  test("an Outcome added under an Objective is parented to it") {
    val db = tempDb()
    run(db, "career", "add", "become a staff engineer")
    run(db, "objective", "add", "ship the migration", "--goal", "g1")

    val (code, out, _) = run(db, "outcome", "add", "--objective", "obj1", "write the migration")

    assertEquals(code, 0)
    assertEquals(out.trim, "out1")
    assertEquals(
      rows(db, "SELECT objective_id, emergency FROM outcome WHERE id = 'out1'"),
      List(List("obj1", "0"))
    )
  }

  test("an emergency Outcome carries no Objective, no Why and no Verification") {
    val db = tempDb()

    val (code, out, _) = run(db, "outcome", "add", "--emergency", "fix production")

    assertEquals(code, 0)
    assertEquals(out.trim, "out1")
    assertEquals(
      rows(db, "SELECT objective_id, emergency, why FROM outcome WHERE id = 'out1'"),
      List(List("null", "1", "null"))
    )
    assertEquals(rows(db, "SELECT statement FROM outcome_verification"), Nil)
  }

  test("an emergency Outcome refuses a Why or an Objective") {
    val db = tempDb()
    run(db, "career", "add", "become a staff engineer")
    run(db, "objective", "add", "ship the migration", "--goal", "g1")

    val (whyCode, _, whyErr) =
      run(db, "outcome", "add", "--emergency", "fix production", "--why", "it is down")
    assertEquals(whyCode, 2)
    assert(whyErr.contains("carries no --why"), whyErr)

    val (objectiveCode, _, objectiveErr) =
      run(db, "outcome", "add", "--emergency", "--objective", "obj1", "fix production")
    assertEquals(objectiveCode, 2)
    assert(objectiveErr.contains("no --objective"), objectiveErr)

    assertEquals(rows(db, "SELECT id FROM outcome"), Nil)
  }

  test("an Objective from another Quarter cannot parent an Outcome") {
    val db = tempDb()
    run(db, "career", "add", "become a staff engineer")
    execute(
      db,
      """INSERT INTO objective(id, title, goal_id, quarter, why)
        |VALUES ('obj1', 'old objective', 'g1', '2025-Q1', NULL)""".stripMargin
    )

    val (code, _, err) = run(db, "outcome", "add", "--objective", "obj1", "stale work")

    assertEquals(code, 1)
    assert(err.contains("committed in 2025-Q1"), err)
    assertEquals(rows(db, "SELECT id FROM outcome"), Nil)
  }

  test("an unknown Objective is a domain error, not a crash") {
    val (code, _, err) = run(tempDb(), "outcome", "add", "--objective", "obj9", "work")

    assertEquals(code, 1)
    assert(err.contains("no Quarterly Objective obj9"), err)
  }

  // --- Backlog ------------------------------------------------------------

  test("backlog marks doing in place and hides terminal Outcomes unless --all") {
    val db = tempDb()
    run(db, "outcome", "add", "first")
    run(db, "outcome", "add", "second")
    run(db, "outcome", "add", "third")
    execute(db, "UPDATE outcome SET stage = 'doing' WHERE id = 'out2'")
    execute(db, "UPDATE outcome SET status = 'dropped' WHERE id = 'out3'")

    assertEquals(
      run(db, "backlog", "list")._2.trim.linesIterator.toList,
      List("1  out1  first", "2  out2  second  (doing)")
    )
    assertEquals(
      run(db, "backlog", "--all")._2.trim.linesIterator.toList,
      List("1  out1  first", "2  out2  second  (doing)", "3  out3  third  (dropped)")
    )
  }

  test("an empty backlog names the Quarter it is empty in") {
    val (code, out, _) = run(tempDb(), "backlog")

    assertEquals(code, 0)
    assertEquals(out.trim, s"no open Outcomes in ${Quarter.current}")
  }

  test("backlog rejects an unknown argument") {
    val (code, _, err) = run(tempDb(), "backlog", "--sorted")

    assertEquals(code, 2)
    assert(err.contains("unknown argument: --sorted"), err)
  }

  test("usage names the commands this slice adds") {
    val (_, out, _) = run(tempDb(), "--help")

    assert(out.contains("rocket objective add"), out)
    assert(out.contains("rocket outcome add"), out)
    assert(out.contains("rocket backlog"), out)
  }
}
