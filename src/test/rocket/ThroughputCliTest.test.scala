package rocket

import java.nio.file.Path

class ThroughputCliTest extends CliHarness {

  private def threeOutcomes(): Path = {
    val db = tempDb()
    run(db, "outcome", "add", "first")
    run(db, "outcome", "add", "second")
    run(db, "outcome", "add", "third")
    db
  }

  // --- progress -----------------------------------------------------------

  test("progress start moves an Outcome to doing, which the queue marks in place") {
    val db = tempDb()
    run(db, "outcome", "add", "first")

    val (code, out, _) = run(db, "progress", "start", "out1")

    assertEquals(code, 0)
    assertEquals(out.trim, "out1 doing")
    assertEquals(run(db, "backlog")._2.trim, "1  out1  first  (doing)")
  }

  test("progress box records a Time Box in the current Quarter and starts the work") {
    val db = tempDb()
    run(db, "outcome", "add", "first")

    val (firstCode, firstOut, _) = run(db, "progress", "box", "out1")
    val (secondCode, secondOut, _) = run(db, "progress", "box", "out1")

    assertEquals(firstCode, 0)
    assertEquals(firstOut.trim, "out1 1 box")
    assertEquals(secondCode, 0)
    assertEquals(secondOut.trim, "out1 2 boxes")
    assertEquals(
      rows(db, "SELECT quarter, substr(recorded_at, 1, 4) FROM time_box ORDER BY id"),
      List(
        List(Quarter.current, Quarter.current.take(4)),
        List(Quarter.current, Quarter.current.take(4))
      )
    )
    assertEquals(rows(db, "SELECT stage FROM outcome WHERE id = 'out1'"), List(List("doing")))
  }

  test("progress achieve sets status, freezes stage and refuses further Time Boxes") {
    val db = tempDb()
    run(db, "outcome", "add", "first")
    run(db, "progress", "start", "out1")

    val (code, out, _) = run(db, "progress", "achieve", "out1")

    assertEquals(code, 0)
    assertEquals(out.trim, "out1 achieved")
    assertEquals(
      rows(db, "SELECT status, stage FROM outcome WHERE id = 'out1'"),
      List(List("achieved", "doing"))
    )

    val (boxCode, _, boxErr) = run(db, "progress", "box", "out1")
    assertEquals(boxCode, 1)
    assert(boxErr.contains("out1 is already achieved"), boxErr)
    assertEquals(rows(db, "SELECT COUNT(*) FROM time_box"), List(List("0")))
  }

  test("an Outcome can be achieved without ever being started") {
    val db = tempDb()
    run(db, "outcome", "add", "first")

    val (code, _, _) = run(db, "progress", "achieve", "out1")

    assertEquals(code, 0)
    assertEquals(
      rows(db, "SELECT status, stage FROM outcome WHERE id = 'out1'"),
      List(List("achieved", "todo"))
    )
  }

  test("dropping an Outcome keeps the Time Boxes it already recorded") {
    val db = tempDb()
    run(db, "outcome", "add", "first")
    run(db, "progress", "box", "out1")

    val (code, dropOut, _) = run(db, "progress", "drop", "out1")

    assertEquals(code, 0)
    assertEquals(dropOut.trim, "out1 dropped")
    assertEquals(rows(db, "SELECT COUNT(*) FROM time_box"), List(List("1")))
    assertEquals(run(db, "backlog")._2.trim, s"no open Outcomes in ${Quarter.current}")
  }

  test("progress on an unknown Outcome is a domain error") {
    val (code, _, err) = run(tempDb(), "progress", "start", "out9")

    assertEquals(code, 1)
    assert(err.contains("no Outcome out9"), err)
  }

  test("progress on an ambiguous prefix is an error rather than a guess") {
    val db = tempDb()
    run(db, "outcome", "add", "first")
    run(db, "outcome", "add", "second")

    val (code, _, err) = run(db, "progress", "start", "out")

    assertEquals(code, 1)
    assert(err.contains("matches out1, out2"), err)
  }

  test("progress without a verb is a usage error that prints its help") {
    val (code, _, err) = run(tempDb(), "progress")

    assertEquals(code, 2)
    assert(err.contains("rocket progress start|box|achieve|drop <id>"), err)
  }

  // --- backlog reorder ----------------------------------------------------

  test("backlog reorder moves an Outcome and renumbers the queue contiguously") {
    val db = threeOutcomes()

    val (code, out, _) = run(db, "backlog", "reorder", "out3", "-1")

    assertEquals(code, 0)
    assertEquals(out.trim, "out3 2")
    assertEquals(
      rows(db, "SELECT id, rank FROM outcome ORDER BY rank"),
      List(List("out1", "1"), List("out3", "2"), List("out2", "3"))
    )
    assertEquals(
      run(db, "backlog")._2.trim.linesIterator.toList,
      List("1  out1  first", "2  out3  third", "3  out2  second")
    )
  }

  test("a move past either end of the queue stops at the end") {
    val db = threeOutcomes()

    assertEquals(run(db, "backlog", "reorder", "out1", "-5")._2.trim, "out1 1")
    assertEquals(run(db, "backlog", "reorder", "out1", "+9")._2.trim, "out1 3")
    assertEquals(
      rows(db, "SELECT id FROM outcome ORDER BY rank"),
      List(List("out2"), List("out3"), List("out1"))
    )
  }

  test("a terminal Outcome keeps its slot while the open ones move around it") {
    val db = threeOutcomes()
    run(db, "progress", "drop", "out2")

    val (code, out, _) = run(db, "backlog", "reorder", "out3", "-1")

    assertEquals(code, 0)
    assertEquals(out.trim, "out3 1")
    assertEquals(
      rows(db, "SELECT id, rank FROM outcome ORDER BY rank"),
      List(List("out3", "1"), List("out2", "2"), List("out1", "3"))
    )
    assertEquals(
      run(db, "backlog", "--all")._2.trim.linesIterator.toList,
      List("1  out3  third", "2  out2  second  (dropped)", "3  out1  first")
    )
  }

  test("a terminal Outcome cannot be reordered") {
    val db = threeOutcomes()
    run(db, "progress", "drop", "out3")

    val (code, _, err) = run(db, "backlog", "reorder", "out3", "-1")

    assertEquals(code, 1)
    assert(err.contains("out3 is already dropped"), err)
  }

  test("a move must be written +N or -N") {
    val db = threeOutcomes()

    val (code, _, err) = run(db, "backlog", "reorder", "out1", "2")

    assertEquals(code, 2)
    assert(err.contains("write +N or -N"), err)
  }
}
