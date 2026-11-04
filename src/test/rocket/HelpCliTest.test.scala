package rocket

import java.nio.file.Files

/** The help surface: what a noun's `--help` prints, and what a wrong verb prints. */
class HelpCliTest extends CliHarness {

  private val nouns = List(
    "career",
    "quarter",
    "objective",
    "outcome",
    "inbox",
    "backlog",
    "progress",
    "trace"
  )

  /** Nouns whose bare form is a command in its own right rather than an error. */
  private val nounsWithADefault = Set("quarter", "backlog")

  private def commandLines(output: String): List[String] =
    output.linesIterator.filter(_.startsWith("  rocket ")).toList

  test("every noun answers --help and -h with its own help, and succeeds") {
    nouns.foreach { noun =>
      List("--help", "-h").foreach { flag =>
        val (code, out, err) = run(tempDb(), noun, flag)

        assertEquals(code, 0, s"rocket $noun $flag")
        assertEquals(err, "", s"rocket $noun $flag")
        assert(out.startsWith(s"rocket $noun - "), out)
      }
    }
  }

  test("a noun's help names only that noun's verbs") {
    nouns.foreach { noun =>
      val (_, out, _) = run(tempDb(), noun, "--help")

      assert(commandLines(out).nonEmpty, out)
      commandLines(out).foreach(line => assert(line.startsWith(s"  rocket $noun"), line))
    }
  }

  test("a noun's help renders the same lines the whole surface holds for it") {
    val (_, whole, _) = run(tempDb(), "--help")
    val surface = whole.linesIterator.toList

    nouns.foreach { noun =>
      val (_, own, _) = run(tempDb(), noun, "--help")

      commandLines(own).foreach(line => assert(surface.contains(line), s"$line is not in --help"))
    }
  }

  test("the whole surface still names every noun under its heading") {
    val (_, out, _) = run(tempDb(), "--help")

    assertEquals(
      out.linesIterator.take(3).toList,
      List("rocket - planning and throughput for a career", "", "Planning Mode")
    )
    assert(out.contains("\nThroughput Mode\n"), out)
    nouns.foreach(noun => assert(out.contains(s"rocket $noun"), noun))
  }

  test("a bare noun is a usage error that prints that noun's help") {
    (nouns.toSet -- nounsWithADefault).toList.sorted.foreach { noun =>
      val (code, out, err) = run(tempDb(), noun)

      assertEquals(code, 2, noun)
      assertEquals(out, "", noun)
      assert(err.startsWith(s"rocket $noun - "), err)
    }
  }

  test("quarter and backlog still answer a bare noun, as before") {
    val db = tempDb()

    assertEquals(run(db, "quarter")._2.trim, Quarter.current)
    assertEquals(run(db, "backlog")._2.trim, s"no open Outcomes in ${Quarter.current}")
  }

  test("an unknown verb prints the error and that noun's help, not the whole surface") {
    (nouns.toSet - "trace").toList.sorted.foreach { noun =>
      val (code, out, err) = run(tempDb(), noun, "sideways")

      assertEquals(code, 2, noun)
      assertEquals(out, "", noun)
      assert(err.contains("sideways"), err)
      assert(err.contains(s"rocket $noun - "), err)
      assert(!err.contains("Planning Mode"), err)
    }
  }

  test("trace rejects what it cannot read as an Outcome id") {
    val db = tempDb()

    val (extraCode, _, extraErr) = run(db, "trace", "out1", "out2")
    assertEquals(extraCode, 2)
    assert(extraErr.contains("rocket trace <outcome-id>"), extraErr)

    val (flagCode, _, flagErr) = run(db, "trace", "--bogus")
    assertEquals(flagCode, 2)
    assert(flagErr.contains("unknown argument: --bogus"), flagErr)
  }

  test("noun help opens no store, so it rolls nothing over") {
    val db = tempDb()

    nouns.foreach { noun =>
      val (code, out, _) = run(db, noun, "--help")

      assertEquals(code, 0, noun)
      assert(!out.contains("Notice:"), out)
      assert(!Files.exists(db), s"rocket $noun --help created $db")
    }
  }
}
