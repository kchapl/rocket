package rocket

/** The record's own rules, checked without a store: what may be planned, what may be reparented. */
class DomainTest extends munit.FunSuite {

  private val quarter = "2026-Q4"

  private def objective(id: String, in: String): QuarterlyObjective =
    QuarterlyObjective(id, "ship the migration", "g1", in, None, Nil)

  test("an emergency Outcome is planned with no Objective, Why or Verification") {
    val planned = Outcome.planned(
      "prod is down",
      quarter,
      objectiveId = Some("obj1"),
      emergency = true,
      why = Some("it is down"),
      verification = List("the pager stops")
    )

    assertEquals(planned.objectiveId, None)
    assertEquals(planned.why, None)
    assertEquals(planned.verification, Nil)
    assertEquals(
      planned.discarded,
      Set(Outcome.Field.Objective, Outcome.Field.Why, Outcome.Field.Verification)
    )
    assertEquals(
      planned.refusal,
      Some("an emergency Outcome carries no Objective, no Why and no Verification")
    )

    val stored = planned.stored("out1", 1)
    assertEquals(stored.emergency, true)
    assertEquals(stored.status, Status.Open)
    assertEquals(stored.stage, Stage.Todo)
  }

  test("an unfiled Outcome keeps the Why and Verification it was planned with") {
    val planned = Outcome.planned(
      "write the design doc",
      quarter,
      objectiveId = None,
      emergency = false,
      why = Some("so review has something to read"),
      verification = List("review reads it")
    )

    assertEquals(planned.discarded, Set.empty[Outcome.Field])
    assertEquals(planned.refusal, None)
    assertEquals(planned.why, Some("so review has something to read"))
    assertEquals(planned.verification, List("review reads it"))
  }

  test("planning names only the fields an emergency actually dropped") {
    val whyOnly =
      Outcome.planned("prod is down", quarter, None, emergency = true, Some("it is down"), Nil)
    assertEquals(whyOnly.refusal, Some("an emergency Outcome carries no Why"))

    val objectiveOnly =
      Outcome.planned("prod is down", quarter, Some("obj1"), emergency = true, None, Nil)
    assertEquals(objectiveOnly.refusal, Some("an emergency Outcome carries no Objective"))
  }

  test("a reparented Outcome clears the emergency flag and keeps its empty Why") {
    val planned = Outcome.planned("prod is down", quarter, None, emergency = true, None, Nil)
    val emergency = planned.stored("out1", 1)

    assertEquals(
      Outcome.reparented(emergency, objective("obj1", quarter)),
      Right(emergency.copy(objectiveId = Some("obj1"), emergency = false))
    )
    assertEquals(Outcome.reparented(emergency, objective("obj1", quarter)).toOption.get.why, None)
  }

  test("an Objective from another Quarter cannot parent an Outcome") {
    val planned = Outcome.planned("stale work", quarter, None, emergency = false, None, Nil)
    val unrelated = planned.stored("out1", 1)

    val refused = Outcome.reparented(unrelated, objective("obj1", "2025-Q1"))

    assert(refused.isLeft, refused)
    assert(refused.left.exists(_.contains("committed in 2025-Q1")), refused)
  }
}
