package rocket

/** The one place a write's facts become the line a person reads. */
object Report {

  def line(change: Change): String = change match {
    case Change.Created(id) => id
    case Change.Renamed(id) => s"$id renamed"
    case Change.Archived(id, true) => s"archived $id"
    case Change.Archived(id, false) => s"unarchived $id"
    case Change.OutcomeStarted(id) => s"$id doing"
    case Change.TimeBoxesRecorded(id, count) =>
      s"$id $count ${if (count == 1) "box" else "boxes"}"
    case Change.OutcomeAchieved(id) => s"$id achieved"
    case Change.OutcomeDropped(id) => s"$id dropped"
    case Change.ObjectiveDropped(id, count) =>
      s"$id dropped $count ${if (count == 1) "outcome" else "outcomes"}"
    case Change.OutcomeParented(outcomeId, objectiveId) =>
      s"$outcomeId parented to $objectiveId"
    case Change.BacklogReordered(id, rank) => s"$id $rank"
    case Change.IdeaPromoted(_, targetId) => targetId
    case Change.IdeaDiscarded(id) => s"$id discarded"
    case Change.QuartersClosed(from, through, carried, current) =>
      val closed = if (from == through) from else s"$from–$through"
      val noun = if (carried == 1) "open outcome" else "open outcomes"
      s"Notice: $closed closed. Carried forward $carried $noun into $current."
  }
}
