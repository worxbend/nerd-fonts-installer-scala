package io.worxbend.nerdfonts.cli

import io.worxbend.nerdfonts.fonts.DryRun
import io.worxbend.nerdfonts.picker.IconMode

import scala.annotation.unused

import picocli.CommandLine.Command
import picocli.CommandLine.Option as CliOption
import picocli.CommandLine.Parameters

/**
 * The picocli root command. It does nothing but collect option values; picocli binds them by calling the
 * annotated setters, which is why this is the one class in the codebase allowed a `var` (SPEC §2). The
 * `--icons` value is kept raw because the reference validates it after parsing and quotes the original text.
 * Every option carries an explicit `order`: picocli lists setter-bound options in reflection order, which the
 * JVM does not define, so without it the usage text could differ between two builds. The order is Go's
 * (`flag` sorts alphabetically), with `--help` last because Go does not list it at all.
 *
 * This is the one class named in `reflect-config.json` and asserted by `MainSuite`, which is why it gets its
 * own file named after itself rather than sharing `Cli.scala`.
 */
@Command(
  name = "nerd-fonts-installer",
  header = Array("Nerd Fonts, installed the boring way."),
  description = Array("Install Nerd Fonts from a config file or an interactive picker."),
  sortOptions = false,
  mixinStandardHelpOptions = false,
)
@SuppressWarnings(
  Array("scalafix:DisableSyntax.var"),
) // picocli binds options through setters into this one field.
final private[cli] class RootCommand:
  private var draft: OptionDraft = OptionDraft.defaults

  @CliOption(
    names = Array("-config", "--config"),
    order = 0,
    paramLabel = "<path>",
    description = Array(
      "config file; when omitted, discover an app-named config in CWD or the user config directory",
    ),
  )
  def setConfig(value: String): Unit = draft = draft.copy(explicitConfig = Some(value))

  @CliOption(
    names = Array("-dry-run", "--dry-run"),
    order = 1,
    description = Array("print planned downloads without installing fonts"),
  )
  def setDryRun(value: Boolean): Unit = draft = draft.copy(dryRun = DryRun.fromBoolean(value))

  @CliOption(
    names = Array("-font-names", "--font-names"),
    order = 2,
    description = Array("print YAML-ready Nerd Font family names and exit"),
  )
  def setFontNames(value: Boolean): Unit =
    draft = draft.copy(mode = if value then CliMode.FontNames else CliMode.Install)

  @CliOption(
    names = Array("-interactive", "--interactive"),
    order = 4,
    description = Array("start the terminal picker when no config file is found"),
  )
  def setInteractive(value: Boolean): Unit = draft = draft.copy(interactive = Interactive.fromBoolean(value))

  @CliOption(
    names = Array("-icons", "--icons"),
    order = 3,
    paramLabel = "<mode>",
    description = Array("interactive icon mode: auto, nerd, unicode, or ascii (default: auto)"),
  )
  def setIcons(value: String): Unit = draft = draft.copy(icons = value)

  @CliOption(
    names = Array("-version", "--version"),
    order = 5,
    versionHelp = true,
    description = Array("print version information and exit"),
  )
  def setVersion(@unused value: Boolean): Unit = ()

  @CliOption(
    names = Array("-h", "-help", "--help"),
    order = 6,
    usageHelp = true,
    description = Array("print this help and exit"),
  )
  def setHelp(@unused value: Boolean): Unit = ()

  // Go's `flag` stops at the first positional and leaves the rest to the caller, which ignores them.
  @Parameters(arity = "0..*", hidden = true)
  def setIgnored(@unused values: Array[String]): Unit = ()

  def rawIcons: String = draft.icons

  def options(icons: IconMode): CliOptions =
    CliOptions(draft.explicitConfig, draft.mode, draft.dryRun, draft.interactive, icons)

/** The values picocli has bound so far; the Go flag defaults until a setter runs. */
final private[cli] case class OptionDraft(
    explicitConfig: Option[String],
    mode: CliMode,
    dryRun: DryRun,
    interactive: Interactive,
    icons: String,
)

private[cli] object OptionDraft:
  val defaults: OptionDraft =
    OptionDraft(None, CliMode.Install, DryRun.Disabled, Interactive.NotRequested, IconMode.Auto.render)
