package io.worxbend.nerdfonts.picker

/**
 * The glyphs one icon mode draws with. Reference data copied verbatim from the Go `icons.go`, written as
 * Unicode escapes so the private-use Nerd Font code points survive editors and diff tools that cannot show
 * them. Adding a font means adding a row to [[IconSet.nerdFamilyGlyphs]], not a branch.
 */
final private[picker] case class IconSet(
    mode: IconMode,
    title: String,
    pkg: String,
    release: String,
    font: String,
    folder: String,
    checked: String,
    unchecked: String,
    selected: String,
    ready: String,
    launch: String,
    toolbox: String,
    separator: String,
    nerdFamily: Map[String, String],
):
  /** Go `iconForFamily`: the family key is lower-cased with spaces removed; unknown families get the font glyph. */
  def iconForFamily(family: String): String = nerdFamily.getOrElse(family.replace(" ", "").toLowerCase, font)

  /** The wordmark's logo; ASCII mode must not rely on the star glyph. */
  def logo: String = mode match
    case IconMode.Ascii => "[NF]"
    case _              => "\u2726 NF \u2726"

private[picker] object IconSet:
  /** Normalised family key (lower-case, no spaces) to Nerd Font glyph; used only by the `nerd` set. */
  val nerdFamilyGlyphs: Map[String, String] = Map(
    "0xproto"         -> "\ue656",
    "adwaitamono"     -> "\ue712",
    "anonymouspro"    -> "\udb80\ude19",
    "caskaydiacove"   -> "\ue795",
    "cascadiacode"    -> "\ue795",
    "cascadiamono"    -> "\ue795",
    "firacode"        -> "\ue7a7",
    "firago"          -> "\ue7a7",
    "hack"            -> "\udb80\udf0c",
    "ibmplexmono"     -> "\udb82\udc71",
    "iosevka"         -> "\udb81\ude26",
    "jetbrainsmono"   -> "\ue70c",
    "meslo"           -> "\ue795",
    "monaspace"       -> "\ue709",
    "robotomono"      -> "\udb85\udea4",
    "saucecodepro"    -> "\ue716",
    "spacemono"       -> "\udb80\udf86",
    "symbolsnerdfont" -> "\udb82\udcc6",
    "ubuntu"          -> "\uf31b",
    "ubuntumono"      -> "\uf31b",
    "victormono"      -> "\udb81\ude26",
  )

  val nerd: IconSet = IconSet(
    mode = IconMode.Nerd,
    title = "\udb81\uded6",
    pkg = "\uf487",
    release = "\udb81\udc15",
    font = "\uf031",
    folder = "\uf07c",
    checked = "\udb80\udd32",
    unchecked = "\udb80\udd31",
    selected = "\u2705",
    ready = "\u2705",
    launch = "\ud83d\ude80",
    toolbox = "\ud83e\uddf0",
    separator = "\u2022",
    nerdFamily = nerdFamilyGlyphs,
  )

  val ascii: IconSet = IconSet(
    mode = IconMode.Ascii,
    title = "NF",
    pkg = "pkg",
    release = "tag",
    font = "Aa",
    folder = "dir",
    checked = "[x]",
    unchecked = "[ ]",
    selected = "OK",
    ready = "OK",
    launch = ">>",
    toolbox = "tools",
    separator = "-",
    nerdFamily = Map.empty,
  )

  /** The safe default (also `auto`): expressive glyphs that render without a patched font. */
  val unicode: IconSet = IconSet(
    mode = IconMode.Unicode,
    title = "\u2726",
    pkg = "\u25a3",
    release = "\u25c6",
    font = "Aa",
    folder = "\u2302",
    checked = "\u2611",
    unchecked = "\u2610",
    selected = "\u2713",
    ready = "\u2713",
    launch = "\u2192",
    toolbox = "\u25c7",
    separator = "\u2022",
    nerdFamily = Map.empty,
  )

  def forMode(mode: IconMode): IconSet = mode match
    case IconMode.Nerd                    => nerd
    case IconMode.Ascii                   => ascii
    case IconMode.Auto | IconMode.Unicode => unicode
