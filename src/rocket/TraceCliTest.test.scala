package rocket

class TraceCliTest extends CliHarness {

  test("trace shows the whole chain from the Goal down to the Outcome") {
    val db = tempDb()
    run(db, "career", "add", "become a staff engineer")
    run(db, "objective", "add", "ship the migration", "--goal", "g1")
    run(db, "outcome", "add", "--objective", "obj1", "write the plan")

    val (code, out, _) = run(db, "trace", "out1")

    assertEquals(code, 0)
    assertEquals(
      out.trim.linesIterator.toList,
      List("g1  become a staff engineer", "  obj1  ship the migration", "    out1  write the plan")
    )
  }

  test("a parentless Outcome traces to itself") {
    val db = tempDb()
    run(db, "outcome", "add", "unfiled thought")

    val (code, out, _) = run(db, "trace", "out1")

    assertEquals(code, 0)
    assertEquals(out.trim, "out1  unfiled thought  (parentless)")
  }

  test("an emergency Outcome traces to itself, flagged as an emergency and not as parentless") {
    val db = tempDb()
    run(db, "outcome", "add", "--emergency", "fix production")

    val (code, out, _) = run(db, "trace", "out1")

    assertEquals(code, 0)
    assertEquals(out.trim, "out1  fix production  (emergency)")
  }

  test("trace carries the Outcome's Stage and Status, and they survive the Goal's rename") {
    val db = tempDb()
    run(db, "career", "add", "become a staff engineer")
    run(db, "objective", "add", "ship the migration", "--goal", "g1")
    run(db, "outcome", "add", "--objective", "obj1", "write the plan")
    run(db, "progress", "start", "out1")
    run(db, "progress", "achieve", "out1")
    run(db, "career", "rename", "g1", "become a principal engineer")

    val (code, out, _) = run(db, "trace", "out1")

    assertEquals(code, 0)
    assertEquals(
      out.trim.linesIterator.toList,
      List(
        "g1  become a principal engineer",
        "  obj1  ship the migration",
        "    out1  write the plan  (doing)  (achieved)"
      )
    )
  }

  test("trace follows the current parent after rollover re-commits the Objective") {
    val db = tempDb()
    run(db, "career", "add", "become a staff engineer")
    run(db, "objective", "add", "ship the migration", "--goal", "g1")
    run(db, "outcome", "add", "--objective", "obj1", "write the plan")
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
        "  obj2  ship the migration",
        "    out1  write the plan"
      )
    )
  }

  test("trace reports an unknown Outcome and a missing id") {
    val db = tempDb()

    val (unknownCode, _, unknownErr) = run(db, "trace", "out9")
    assertEquals(unknownCode, 1)
    assert(unknownErr.contains("no Outcome out9"), unknownErr)

    val (usageCode, _, usageErr) = run(db, "trace")
    assertEquals(usageCode, 2)
    assert(usageErr.contains("rocket trace <outcome-id>"), usageErr)
  }
}
