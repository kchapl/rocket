package rocket

/**
 * Compact JSON, one line per read command: enough for a pipeline to read, and
 * no dependency for the native binary to carry.
 */
object Json {

  /** A JSON string, escaped so any title the record accepts survives `jq`. */
  def str(text: String): String = {
    val escaped = new StringBuilder("\"")
    text.foreach {
      case '"'          => escaped.append("\\\"")
      case '\\'         => escaped.append("\\\\")
      case '\n'         => escaped.append("\\n")
      case '\r'         => escaped.append("\\r")
      case '\t'         => escaped.append("\\t")
      case '\b'         => escaped.append("\\b")
      case '\f'         => escaped.append("\\f")
      case control if control < ' ' => escaped.append("\\u%04x".format(control.toInt))
      case other        => escaped.append(other)
    }
    escaped.append('"').toString
  }

  def num(value: Int): String = value.toString

  def bool(value: Boolean): String = value.toString

  /** A field that may be absent from the record, which JSON spells as `null`. */
  def optional(text: Option[String]): String = text.fold("null")(str)

  def obj(fields: (String, String)*): String =
    fields.map { case (name, value) => s"${str(name)}:$value" }.mkString("{", ",", "}")

  def arr(items: Iterable[String]): String = items.mkString("[", ",", "]")

  def goal(goal: CareerGoal): String =
    obj(
      "id" -> str(goal.id),
      "title" -> str(goal.title),
      "why" -> optional(goal.why),
      "verification" -> arr(goal.verification.map(str)),
      "archived" -> bool(goal.archived)
    )

  def objective(objective: QuarterlyObjective): String =
    obj(
      "id" -> str(objective.id),
      "title" -> str(objective.title),
      "goalId" -> str(objective.goalId),
      "quarter" -> str(objective.quarter),
      "why" -> optional(objective.why),
      "verification" -> arr(objective.verification.map(str))
    )

  def outcome(outcome: Outcome): String =
    obj(
      "id" -> str(outcome.id),
      "title" -> str(outcome.title),
      "quarter" -> str(outcome.quarter),
      "objectiveId" -> optional(outcome.objectiveId),
      "emergency" -> bool(outcome.emergency),
      "why" -> optional(outcome.why),
      "verification" -> arr(outcome.verification.map(str)),
      "rank" -> num(outcome.rank),
      "status" -> str(outcome.status.wire),
      "stage" -> str(outcome.stage.wire)
    )

  def idea(idea: Idea): String =
    obj(
      "id" -> str(idea.id),
      "text" -> str(idea.text),
      "disposition" -> str(idea.disposition.wire),
      "promotedTo" -> optional(idea.promotedTo),
      "capturedAt" -> str(idea.capturedAt)
    )

  def gaps(gaps: Gaps): String =
    obj(
      "quarter" -> str(gaps.quarter),
      "unalignedGoals" -> arr(gaps.unalignedGoals.map(goal)),
      "emptyObjectives" -> arr(gaps.emptyObjectives.map(objective)),
      "parentlessOutcomes" -> arr(gaps.parentlessOutcomes.map(outcome))
    )

  def lineage(lineage: Lineage): String =
    obj(
      "goal" -> lineage.goal.fold("null")(goal),
      "objective" -> lineage.objective.fold("null")(objective),
      "outcome" -> outcome(lineage.outcome)
    )

  def timeBox(box: TimeBox): String =
    obj(
      "outcomeId" -> str(box.outcomeId),
      "quarter" -> str(box.quarter),
      "recordedAt" -> str(box.recordedAt)
    )

  // --- Reading ----------------------------------------------------------

  /** A parsed JSON value, kept only as deep as import needs to look. */
  sealed trait JsonValue

  final case class JsonObject(fields: Map[String, JsonValue]) extends JsonValue
  final case class JsonArray(items: List[JsonValue]) extends JsonValue
  final case class JsonString(value: String) extends JsonValue
  final case class JsonNumber(value: BigDecimal) extends JsonValue
  final case class JsonBoolean(value: Boolean) extends JsonValue
  case object JsonNull extends JsonValue

  /** Parses one JSON document, reporting where the text stopped making sense. */
  def parse(text: String): Either[String, JsonValue] = {
    val parser = new Parser(text)
    try {
      parser.skipSpace()
      val value = parser.value()
      parser.skipSpace()
      if (!parser.atEnd) Left(s"unexpected text after the document at ${parser.position}")
      else Right(value)
    } catch {
      case error: ParseError => Left(error.getMessage)
    }
  }

  private final class ParseError(message: String) extends RuntimeException(message)

  private final class Parser(text: String) {

    private var index = 0

    def atEnd: Boolean = index >= text.length

    def position: Int = index

    private def fail(problem: String): Nothing = throw new ParseError(s"$problem at $index")

    private def peek: Char = if (atEnd) fail("unexpected end of input") else text.charAt(index)

    private def take(): Char = {
      val char = peek
      index += 1
      char
    }

    private def expect(char: Char): Unit =
      if (take() != char) fail(s"expected '$char'")

    def skipSpace(): Unit = while (!atEnd && text.charAt(index).isWhitespace) index += 1

    def value(): JsonValue = {
      skipSpace()
      peek match {
        case '{'                         => objectValue()
        case '['                         => arrayValue()
        case '"'                        => JsonString(stringValue())
        case 't'                         => literal("true", JsonBoolean(true))
        case 'f'                         => literal("false", JsonBoolean(false))
        case 'n'                         => literal("null", JsonNull)
        case char if char == '-' || char.isDigit => numberValue()
        case other                       => fail(s"unexpected character '$other'")
      }
    }

    private def objectValue(): JsonValue = {
      expect('{')
      skipSpace()
      var fields = Map.empty[String, JsonValue]
      if (peek == '}') {
        take()
        JsonObject(fields)
      } else {
        var finished = false
        while (!finished) {
          skipSpace()
          val name = stringValue()
          if (fields.contains(name)) fail(s"duplicate key \"$name\"")
          skipSpace()
          expect(':')
          fields = fields.updated(name, value())
          skipSpace()
          take() match {
            case ',' => ()
            case '}' => finished = true
            case other => fail(s"expected ',' or '}', found '$other'")
          }
        }
        JsonObject(fields)
      }
    }

    private def arrayValue(): JsonValue = {
      expect('[')
      skipSpace()
      if (peek == ']') {
        take()
        JsonArray(Nil)
      } else {
        val items = List.newBuilder[JsonValue]
        var finished = false
        while (!finished) {
          items += value()
          skipSpace()
          take() match {
            case ',' => skipSpace()
            case ']' => finished = true
            case other => fail(s"expected ',' or ']', found '$other'")
          }
        }
        JsonArray(items.result())
      }
    }

    private def stringValue(): String = {
      expect('"')
      val builder = new StringBuilder
      var finished = false
      while (!finished) {
        take() match {
          case '"' => finished = true
          case '\\' =>
            take() match {
              case '"'  => builder.append('"')
              case '\\' => builder.append('\\')
              case '/'  => builder.append('/')
              case 'b'  => builder.append('\b')
              case 'f'  => builder.append('\f')
              case 'n'  => builder.append('\n')
              case 'r'  => builder.append('\r')
              case 't'  => builder.append('\t')
              case 'u'  =>
                val digits = (1 to 4).map(_ => take()).mkString
                val code =
                  try Integer.parseInt(digits, 16)
                  catch { case _: NumberFormatException => fail(s"bad \\u escape \"$digits\"") }
                builder.append(code.toChar)
              case other => fail(s"bad escape '\\$other'")
            }
          case other => builder.append(other)
        }
      }
      builder.toString
    }

    private def numberValue(): JsonValue = {
      val start = index
      if (!atEnd && text.charAt(index) == '-') index += 1
      while (!atEnd && text.charAt(index).isDigit) index += 1
      if (!atEnd && text.charAt(index) == '.') {
        index += 1
        while (!atEnd && text.charAt(index).isDigit) index += 1
      }
      if (!atEnd && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
        index += 1
        if (!atEnd && (text.charAt(index) == '+' || text.charAt(index) == '-')) index += 1
        while (!atEnd && text.charAt(index).isDigit) index += 1
      }
      val raw = text.substring(start, index)
      try JsonNumber(BigDecimal(raw))
      catch { case _: NumberFormatException => fail(s"bad number \"$raw\"") }
    }

    private def literal(word: String, value: JsonValue): JsonValue =
      if (text.startsWith(word, index)) {
        index += word.length
        value
      } else fail(s"expected \"$word\"")
  }
}
