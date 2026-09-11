package io.worxbend.nerdfonts.install

import io.worxbend.nerdfonts.fonts.DryRun
import io.worxbend.nerdfonts.fonts.FamilyName
import io.worxbend.nerdfonts.fonts.RefreshFontCache
import io.worxbend.nerdfonts.fonts.ReleaseSelector

/**
 * Everything the engine needs for one run.
 *
 * `root` is already absolute: the caller expands `~` through `PathExpander` before building the request, so
 * the engine never sees a `DestinationPath` and never consults the environment. Families are already
 * validated `FamilyName`s, which is what makes `root / family` and the download URL safe to build.
 */
final case class InstallRequest(
    selector: ReleaseSelector,
    root: os.Path,
    families: Vector[FamilyName],
    refreshFontCache: RefreshFontCache,
    dryRun: DryRun,
)
