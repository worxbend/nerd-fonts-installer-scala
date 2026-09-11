package io.worxbend.nerdfonts.app

import java.io.File

import picocli.CommandLine.Command

/**
 * Guards the hand-maintained native-image reflection config: picocli reads a command class reflectively, so
 * a `@Command` class missing from the list compiles and passes every JVM test yet fails in the shipped binary.
 */
final class MainSuite extends munit.FunSuite:
  private val cliPackage = os.RelPath("io/worxbend/nerdfonts/cli")

  private val reflectConfig =
    os.RelPath("app/resources/META-INF/native-image/io.worxbend/nerd-fonts-installer/reflect-config.json")

  test("the main object loads"):
    assertEquals(Class.forName("io.worxbend.nerdfonts.app.Main$").getSimpleName, "Main$")

  test("the native reflection config covers every picocli command class"):
    val commands = cliClasses().filter(Class.forName(_).isAnnotationPresent(classOf[Command]))
    assert(commands.nonEmpty, "no @Command class found on the class path")
    assertEquals(commands.toSet.diff(configuredClasses()), Set.empty[String])

  private def configuredClasses(): Set[String] =
    val root = os.Path(System.getProperty("nerdfonts.repoRoot"))
    ujson.read(os.read(root / reflectConfig)).arr.map(_("name").str).toSet

  // Top-level classes only: `$`-suffixed companions and anonymous classes are never picocli commands.
  private def cliClasses(): Vector[String] = classPathDirectories()
    .map(_ / cliPackage)
    .filter(os.isDir)
    .flatMap(dir =>
      os.list(dir)
        .filter(file => file.ext == "class" && !file.baseName.contains('$'))
        .map(file => s"${cliPackage.segments.mkString(".")}.${file.baseName}"),
    )

  private def classPathDirectories(): Vector[os.Path] = System
    .getProperty("java.class.path")
    .split(File.pathSeparatorChar)
    .toVector
    .filter(_.nonEmpty)
    .map(entry => os.Path(java.nio.file.Path.of(entry).toAbsolutePath))
    .filter(os.isDir)
