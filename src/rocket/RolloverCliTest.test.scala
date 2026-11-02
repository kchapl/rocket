package rocket

import java.nio.file.Path

class RolloverCliTest extends CliHarness {

  /** Rewinds a store so its next command sits a boundary or more behind the calendar. */
  private def stale(db: Path, quarters: Int): String = {
    val previous = back(quarters)
    execute(db, s"UPDATE outcome SET quarter = '$previous'")
    execute(db, s"UPDATE objective SET quarter = '$previous'")
    execute(db, s"UPDATE setting SET value = '$previous' WHERE key = 'carried_through'")
    previous
  }

  private def momentIn(quarter: String): String = {
    val month = (quarter.last.asDigit - 1) * 3 + 1
    f"${quarter.take(4)}-$month%02d-15T10:00:00Z"
  }

  private def withoutNotice(stdout: String): List[String] =
    stdout.linesIterator.filterNot(_.startsWith("Notice:")).filter(_.nonEmpty).toList

  test("Quarter names round-trip through their index") {
    assertEquals(Quarter.index("2026-Q1"), Some(2026 * 4))
    assertEquals(Quarter.index("2026-Q4"), Some(2026 * 4 + 3))
    assertEquals(Quarter.at(2026 * 4 + 3), "2026-Q4")
    assertEquals(Quarter.index("2026-Q5"), None)
    assertEquals(Quarter.index("nonsense"), None)
  }

  test("an open Outcome is carried forward and its Time Boxes stay behind") {
    val db = tempDb()
    run(db, "outcome", "add", "carry me")
    val previous = stale(db, 1)
    execute(
      db,
      s"""INSERT INTO time_box(outcome_id, quarter, recorded_at)
         |VALUES ('out1', '$previous', '${momentIn(previous)}')""".stripMargin
    )

    val (code, out, _) = run(db, "backlog")

    assertEquals(code, 0)
    assert(out.startsWith(s"Notice: $previous closed."), out)
    assert(
      out.contains(s"Carried forward 1 open outcome into ${Quarter.current}."),
      out
    )
    assertEquals(withoutNotice(out), List("1  out1  carry me"))
    assertEquals(rows(db, "SELECT quarter FROM outcome WHERE id = 'out1'"), List(List(Quarter.current)))
    assertEquals(rows(db, "SELECT quarter FROM time_box"), List(List(previous)))
  }

  test("the Objective with live work is re-committed in the new Quarter") {
    val db = tempDb()
    run(db, "career", "add", "become a staff engineer")
    run(
      db,
      "objective",
      "add",
      "ship the migration",
      "--goal",
      "g1",
      "--why",
      "releases hurt",
      "--verification",
      "the old path is gone",
      "--verification",
      "releases need no manual step"
    )
    run(db, "outcome", "add", "--objective", "obj1", "keep going")
    run(db, "outcome", "add", "--objective", "obj1", "already done")
    run(db, "progress", "achieve", "out2")
    val previous = stale(db, 1)

    val (code, out, _) = run(db, "backlog")

    assertEquals(code, 0)
    assert(out.contains("Carried forward 1 open outcome"), out)
    assertEquals(
      rows(
        db,
        s"""SELECT title, goal_id, quarter, why FROM objective
           |WHERE id = 'obj2'""".stripMargin
      ),
      List(List("ship the migration", "g1", Quarter.current, "releases hurt"))
    )
    assertEquals(
      rows(
        db,
        """SELECT ordinal, statement FROM objective_verification
          |WHERE objective_id = 'obj2' ORDER BY ordinal""".stripMargin
      ),
      List(List("1", "the old path is gone"), List("2", "releases need no manual step"))
    )
    assertEquals(rows(db, "SELECT objective_id FROM outcome WHERE id = 'out1'"), List(List("obj2")))
    assertEquals(
      rows(db, "SELECT objective_id, quarter FROM outcome WHERE id = 'out2'"),
      List(List("obj1", previous))
    )
    assertEquals(rows(db, "SELECT quarter FROM objective WHERE id = 'obj1'"), List(List(previous)))
    assertEquals(run(db, "objective", "add", "next commitment", "--goal", "g1")._2.trim, "obj3")
  }

  test("an emergency Outcome is carried with no Objective, and none is minted for it") {
    val db = tempDb()
    run(db, "outcome", "add", "--emergency", "fix production")
    stale(db, 1)

    val (code, out, _) = run(db, "backlog")

    assertEquals(code, 0)
    assertEquals(withoutNotice(out), List("1  out1  fix production  (emergency)"))
    assertEquals(
      rows(db, "SELECT quarter, objective_id, emergency FROM outcome WHERE id = 'out1'"),
      List(List(Quarter.current, "null", "1"))
    )
    assertEquals(rows(db, "SELECT COUNT(*) FROM objective"), List(List("0")))
  }

  test("carried Outcomes take the top of the new queue in their old order") {
    val db = tempDb()
    run(db, "outcome", "add", "first")
    run(db, "outcome", "add", "second")
    run(db, "outcome", "add", "third")
    run(db, "progress", "drop", "out2")
    val previous = stale(db, 1)

    val (code, out, _) = run(db, "backlog", "--all")

    assertEquals(code, 0)
    assertEquals(withoutNotice(out), List("1  out1  first", "2  out3  third"))
    assertEquals(
      rows(db, "SELECT id, quarter, rank FROM outcome ORDER BY id"),
      List(
        List("out1", Quarter.current, "1"),
        List("out2", previous, "2"),
        List("out3", Quarter.current, "2")
      )
    )
  }

  test("a gap of several Quarters prints one notice naming the span") {
    val db = tempDb()
    run(db, "outcome", "add", "carry me")
    val start = stale(db, 3)

    val (_, out, _) = run(db, "backlog")

    val notices = out.linesIterator.filter(_.startsWith("Notice:")).toList
    assertEquals(notices.size, 1)
    assertEquals(
      notices.head,
      s"Notice: $start–${back(1)} closed. Carried forward 1 open outcome into ${Quarter.current}."
    )
  }

  test("rollover runs once, not on every command") {
    val db = tempDb()
    run(db, "outcome", "add", "carry me")
    stale(db, 1)

    val (_, first, _) = run(db, "backlog")
    val (_, second, _) = run(db, "backlog")

    assert(first.contains("Notice:"), first)
    assert(!second.contains("Notice:"), second)
    assertEquals(
      rows(db, "SELECT value FROM setting WHERE key = 'carried_through'"),
      List(List(Quarter.current))
    )
  }

  test("rollover runs before a Throughput command, so the box lands in the current Quarter") {
    val db = tempDb()
    run(db, "outcome", "add", "first")
    stale(db, 1)

    val (code, out, _) = run(db, "progress", "box", "out1")

    assertEquals(code, 0)
    assert(out.contains("Notice:"), out)
    assert(out.contains("out1 1 box"), out)
    assertEquals(rows(db, "SELECT quarter FROM time_box"), List(List(Quarter.current)))
  }

  test("a store already current in its Quarter rolls nothing over") {
    val db = tempDb()
    run(db, "outcome", "add", "first")

    val (code, out, _) = run(db, "career", "list")

    assertEquals(code, 0)
    assert(!out.contains("Notice:"), out)
  }

  test("--help touches no record, so it rolls nothing over") {
    val db = tempDb()
    run(db, "outcome", "add", "first")
    val previous = stale(db, 1)

    val (code, out, _) = run(db, "--help")

    assertEquals(code, 0)
    assert(!out.contains("Notice:"), out)
    assertEquals(
      rows(db, "SELECT value FROM setting WHERE key = 'carried_through'"),
      List(List(previous))
    )
    assertEquals(rows(db, "SELECT quarter FROM outcome WHERE id = 'out1'"), List(List(previous)))
  }
}
