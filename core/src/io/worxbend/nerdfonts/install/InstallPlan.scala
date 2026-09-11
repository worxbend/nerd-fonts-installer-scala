package io.worxbend.nerdfonts.install

import io.worxbend.nerdfonts.fonts.FamilyName
import io.worxbend.nerdfonts.releases.DownloadUrl
import io.worxbend.nerdfonts.releases.ReleaseUrls

/**
 * What a run will do, computed before anything touches the network or the disk.
 *
 * The dry run prints this plan and the real run executes exactly this plan, so the URL and target directory
 * a user saw with `--dry-run` are the values the install uses.
 */
final case class InstallPlan(families: Vector[PlannedFamily])

object InstallPlan:
  /**
   * Pure. Families are de-duplicated preserving first occurrence: config validation already rejects
   * duplicates, but two workers on the same name would collide on `<root>/<Family>`, its staging directory
   * and its `.old` backup, so the plan is the last guard before the fan-out.
   */
  def of(request: InstallRequest): InstallPlan = InstallPlan(
    request.families.distinct.map: family =>
      PlannedFamily(family, ReleaseUrls.download(request.selector, family), request.root / family.value),
  )

/** One family's share of the plan: where its archive comes from and where its fonts end up. */
final case class PlannedFamily(name: FamilyName, url: DownloadUrl, targetDir: os.Path)
