package rocket

class RenameCliTest extends CliHarness {

  test("renaming an Objective rewrites its Claim and nothing else") {
    val db = tempDb()
    run(db, "career", "add", "become a staff engineer")
    run(
      db,
      "objective",
      "add",
      "improve the release process",
      "--goal",
      "g1",
      "--why",
      "releases hurt",
      "--verification",
      "the old path is gone"
    )

    val (code, out, _) =
      run(db, "objective", "rename", "obj1", "releases need no human in the loop")

    assertEquals(code, 0)
    assertEquals(out.trim, "obj1 renamed")
    assertEquals(
      rows(db, "SELECT title, goal_id, quarter, why FROM objective WHERE id = 'obj1'"),
      List(List("releases need no human in the loop", "g1", Quarter.current, "releases hurt"))
    )
    assertEquals(
      rows(db, "SELECT statement FROM objective_verification WHERE objective_id = 'obj1'"),
      List(List("the old path is gone"))
    )
  }

  test("renaming an Outcome keeps its place, Status, Stage and parent") {
    val db = tempDb()
    run(db, "career", "add", "become a staff engineer")
    run(db, "objective", "add", "ship the migration", "--goal", "g1")
    run(db, "outcome", "add", "--objective", "obj1", "ship the migration")
    run(db, "progress", "box", "out1")

    val (code, out, _) =
      run(db, "outcome", "rename", "out1", "the migration runs on the new scheduler")

    assertEquals(code, 0)
    assertEquals(out.trim, "out1 renamed")
    assertEquals(
      rows(db, "SELECT title, rank, status, stage, objective_id FROM outcome WHERE id = 'out1'"),
      List(List("the migration runs on the new scheduler", "1", "open", "doing", "obj1"))
    )
    assertEquals(
      run(db, "backlog")._2.trim,
      "1  out1  the migration runs on the new scheduler  (doing)"
    )
  }

  test("trace reads the Claim as renamed") {
    val db = tempDb()
    run(db, "career", "add", "become a staff engineer")
    run(db, "objective", "add", "improve the release process", "--goal", "g1")
    run(db, "outcome", "add", "--objective", "obj1", "improve the release process")
    run(db, "objective", "rename", "obj1", "releases need no human in the loop")
    run(db, "outcome", "rename", "out1", "the release dashboard has no approval step")

    val (code, out, _) = run(db, "trace", "out1")

    assertEquals(code, 0)
    assertEquals(
      out.trim.linesIterator.toList,
      List(
        "g1  become a staff engineer",
        "  obj1  releases need no human in the loop",
        "    out1  the release dashboard has no approval step"
      )
    )
  }

  test("a re-committed Objective carries the renamed Claim across a boundary") {
    val db = tempDb()
    run(db, "career", "add", "become a staff engineer")
    run(db, "objective", "add", "improve the release process", "--goal", "g1")
    run(db, "outcome", "add", "--objective", "obj1", "improve the release process")
    run(db, "objective", "rename", "obj1", "releases need no human in the loop")
    val previous = back(1)
    execute(db, s"UPDATE outcome SET quarter = '$previous'")
    execute(db, s"UPDATE objective SET quarter = '$previous'")
    execute(db, s"UPDATE setting SET value = '$previous' WHERE key = 'carried_through'")

    val (code, out, _) = run(db, "trace", "out1")

    assertEquals(code, 0)
    assertEquals(
      out.linesIterator.filterNot(_.startsWith("Notice:")).toList,
      List(
        "g1  become a staff engineer",
        "  obj2  releases need no human in the loop",
        "    out1  improve the release process"
      )
    )
  }

  test("a rename resolves the current title by prefix and reports an unknown id") {
    val db = tempDb()
    run(db, "career", "add", "become a staff engineer")
    run(db, "objective", "add", "improve the release process", "--goal", "g1")

    val (code, out, _) =
      run(db, "objective", "rename", "improve", "releases need no human in the loop")
    assertEquals(code, 0)
    assertEquals(out.trim, "obj1 renamed")

    val (unknownObjective, _, objectiveErr) = run(db, "objective", "rename", "obj9", "anything")
    assertEquals(unknownObjective, 1)
    assert(objectiveErr.contains("no Quarterly Objective obj9"), objectiveErr)

    val (unknownOutcome, _, outcomeErr) = run(db, "outcome", "rename", "out9", "anything")
    assertEquals(unknownOutcome, 1)
    assert(outcomeErr.contains("no Outcome out9"), outcomeErr)
  }

  test("a rename without a title, or with a flag, is a usage error") {
    val db = tempDb()
    run(db, "career", "add", "become a staff engineer")
    run(db, "objective", "add", "improve the release process", "--goal", "g1")

    val (emptyCode, _, emptyErr) = run(db, "objective", "rename", "obj1")
    assertEquals(emptyCode, 2)
    assert(emptyErr.contains("a title is required"), emptyErr)

    val (flagCode, _, flagErr) = run(db, "objective", "rename", "obj1", "--why", "x")
    assertEquals(flagCode, 2)
    assert(flagErr.contains("unknown flag: --why"), flagErr)

    val (missingIdCode, _, missingIdErr) = run(db, "outcome", "rename")
    assertEquals(missingIdCode, 2)
    assert(missingIdErr.contains("usage: rocket outcome rename <id> <title>"), missingIdErr)
  }
}
