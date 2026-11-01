package rocket

object Main {

  def main(args: Array[String]): Unit = {
    val cli = new Cli(Db.defaultPath, System.out, System.err)
    System.exit(cli.run(args.toList))
  }
}
