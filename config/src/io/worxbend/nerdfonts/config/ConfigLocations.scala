package io.worxbend.nerdfonts.config

import io.worxbend.nerdfonts.environment.Environment

import scala.util.Try

import zio.IO
import zio.UIO

/**
 * The ordered, de-duplicated list of places a config file is looked for (§4): the working directory first, then
 * the user's config home (`$XDG_CONFIG_HOME` when absolute, else `~/.config`), each with the flat
 * `nerd-fonts-installer.<ext>` files before the `nerd-fonts-installer/config.<ext>` directory shape.
 *
 * The same list feeds discovery and the "no config found" hint, so the hint never names a place discovery does
 * not search. A missing home directory silently drops the config-home half rather than failing discovery;
 * a missing working directory is an error, because the first half cannot be built without it.
 */
object ConfigLocations:
  /** The base name every candidate is derived from; also the directory name of the second shape. */
  val appName: String = "nerd-fonts-installer"

  /** Extensions in probe order; `.conf` and `.hocon` are decoded as HOCON. */
  val extensions: Vector[String] = Vector("yaml", "yml", "json", "conf", "hocon")

  /** The variable that names an explicit config file, checked by the CLI between `--config` and discovery. */
  val configVariable: String = "NERD_FONTS_INSTALLER_CONFIG"

  private val xdgConfigHomeVariable = "XDG_CONFIG_HOME"

  def candidates(env: Environment): IO[ConfigError, Vector[os.Path]] =
    for
      cwd  <- env.workingDirectory.mapError(ConfigError.NoWorkingDirectory(_))
      home <- configHome(env)
    yield (shapes(cwd) ++ home.toVector.flatMap(shapes)).distinct

  private def shapes(base: os.Path): Vector[os.Path] =
    extensions.map(extension => base / s"$appName.$extension") ++
      extensions.map(extension => base / appName / s"config.$extension")

  private def configHome(env: Environment): UIO[Option[os.Path]] =
    for
      xdg  <- absoluteXdgConfigHome(env)
      home <- env.homeDirectory
    yield xdg.orElse(home.map(_ / ".config"))

  // `$XDG_CONFIG_HOME` is honoured only when it is absolute; a relative or unset value is ignored and falls
  // back to `~/.config` rather than being resolved against the working directory.
  private def absoluteXdgConfigHome(env: Environment): UIO[Option[os.Path]] = env
    .variable(xdgConfigHomeVariable)
    .map(_.filter(_.startsWith("/")).flatMap(raw => Try(os.Path(raw)).toOption))
