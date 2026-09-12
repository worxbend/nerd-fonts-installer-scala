package io.worxbend.nerdfonts.app

import zio.ZIO
import zio.test.Spec
import zio.test.TestEnvironment
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

/**
 * Guards the entry point and its native-image contract. The hand-rolled parser uses no reflection (that is
 * why picocli is gone), so the reflection config the shipped binary reads must stay empty: a stray entry
 * would compile and pass every JVM test yet quietly re-introduce a reflective dependency the image cannot
 * satisfy without configuration.
 */
object MainSuite extends ZIOSpecDefault:
  private val reflectConfig =
    os.RelPath("app/resources/META-INF/native-image/io.worxbend/nerd-fonts-installer/reflect-config.json")

  override def spec: Spec[TestEnvironment, Any] = suite("Main")(
    test("the main object loads"):
      ZIO
        .attempt(Class.forName("io.worxbend.nerdfonts.app.Main$").getSimpleName)
        .map(name => assertTrue(name == "Main$"))
    ,
    test("Main is a ZIOAppDefault so the runtime, not a returned value, sets the exit code"):
      assertTrue(Main.isInstanceOf[zio.ZIOAppDefault])
    ,
    test("the native reflection config is empty, proving the parser needs no reflection"):
      ZIO
        .attempt {
          val root = os.Path(System.getProperty("nerdfonts.repoRoot"))
          os.read(root / reflectConfig).replaceAll("\\s", "")
        }
        .map(content => assertTrue(content == "[]")),
  )
