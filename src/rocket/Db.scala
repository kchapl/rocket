package rocket

import java.nio.file.{Files, Path, Paths}
import java.sql.{Connection, DriverManager}

/** Opens the store at `~/.local/share/rocket/rocket.db` and keeps the schema current. */
object Db {

  val SchemaVersion = "1"

  def defaultPath: Path =
    sys.env.get("ROCKET_DB").map(Paths.get(_)).getOrElse {
      val base = sys.env
        .get("XDG_DATA_HOME")
        .map(Paths.get(_))
        .getOrElse(Paths.get(sys.props("user.home"), ".local", "share"))
      base.resolve("rocket").resolve("rocket.db")
    }

  def connect(path: Path): Connection = {
    val abs = path.toAbsolutePath
    Option(abs.getParent).foreach(parent => Files.createDirectories(parent))
    val connection = DriverManager.getConnection(s"jdbc:sqlite:$abs")
    val statement = connection.createStatement()
    try {
      statement.execute("PRAGMA foreign_keys = ON")
      schemaStatements.foreach(statement.execute)
    } finally statement.close()
    seed(connection)
    connection
  }

  private def seed(connection: Connection): Unit = {
    val ps = connection.prepareStatement(
      "INSERT OR IGNORE INTO setting(key, value) VALUES (?, ?)"
    )
    try {
      ps.setString(1, "store_schema_version"); ps.setString(2, SchemaVersion); ps.executeUpdate()
      ps.setString(1, "carried_through"); ps.setString(2, Quarter.current); ps.executeUpdate()
    } finally ps.close()
  }

  /** Mirrors DESIGN.md section 6, with IF NOT EXISTS so opening is idempotent. */
  private val schemaStatements: List[String] = List(
    """CREATE TABLE IF NOT EXISTS goal (
      |  id       TEXT PRIMARY KEY,
      |  title    TEXT NOT NULL,
      |  why      TEXT,
      |  archived INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0, 1))
      |)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS objective (
      |  id      TEXT PRIMARY KEY,
      |  title   TEXT NOT NULL,
      |  goal_id TEXT NOT NULL REFERENCES goal(id),
      |  quarter TEXT NOT NULL,
      |  why     TEXT
      |)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS outcome (
      |  id           TEXT PRIMARY KEY,
      |  title        TEXT NOT NULL,
      |  quarter      TEXT NOT NULL,
      |  objective_id TEXT REFERENCES objective(id),
      |  emergency    INTEGER NOT NULL DEFAULT 0 CHECK (emergency IN (0, 1)),
      |  why          TEXT,
      |  rank         INTEGER NOT NULL,
      |  status       TEXT NOT NULL DEFAULT 'open'
      |                 CHECK (status IN ('open', 'achieved', 'dropped')),
      |  stage        TEXT NOT NULL DEFAULT 'todo'
      |                 CHECK (stage IN ('todo', 'doing')),
      |  CHECK (NOT (objective_id IS NOT NULL AND emergency = 1)),
      |  CHECK (emergency = 0 OR why IS NULL),
      |  UNIQUE (quarter, rank)
      |)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS goal_verification (
      |  goal_id   TEXT NOT NULL REFERENCES goal(id),
      |  ordinal   INTEGER NOT NULL,
      |  statement TEXT NOT NULL,
      |  PRIMARY KEY (goal_id, ordinal)
      |)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS objective_verification (
      |  objective_id TEXT NOT NULL REFERENCES objective(id),
      |  ordinal      INTEGER NOT NULL,
      |  statement    TEXT NOT NULL,
      |  PRIMARY KEY (objective_id, ordinal)
      |)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS outcome_verification (
      |  outcome_id TEXT NOT NULL REFERENCES outcome(id),
      |  ordinal    INTEGER NOT NULL,
      |  statement  TEXT NOT NULL,
      |  PRIMARY KEY (outcome_id, ordinal)
      |)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS time_box (
      |  id          INTEGER PRIMARY KEY,
      |  outcome_id  TEXT NOT NULL REFERENCES outcome(id),
      |  quarter     TEXT NOT NULL,
      |  recorded_at TEXT NOT NULL
      |)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS idea (
      |  id          TEXT PRIMARY KEY,
      |  text        TEXT NOT NULL,
      |  disposition TEXT NOT NULL DEFAULT 'pending'
      |                CHECK (disposition IN ('pending', 'promoted', 'discarded')),
      |  promoted_to TEXT,
      |  captured_at TEXT NOT NULL,
      |  CHECK ((disposition = 'promoted') = (promoted_to IS NOT NULL))
      |)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS setting (
      |  key   TEXT PRIMARY KEY,
      |  value TEXT NOT NULL
      |)""".stripMargin,
    "CREATE INDEX IF NOT EXISTS outcome_objective ON outcome (objective_id)",
    "CREATE INDEX IF NOT EXISTS time_box_outcome ON time_box (outcome_id)",
    "CREATE INDEX IF NOT EXISTS time_box_quarter ON time_box (quarter)",
    "CREATE INDEX IF NOT EXISTS idea_disposition ON idea (disposition)"
  )
}
