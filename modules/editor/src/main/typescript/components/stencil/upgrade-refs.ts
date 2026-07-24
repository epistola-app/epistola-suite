// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import type { TemplateDocument } from '../../types/index.js';

/** A linked stencil's coordinates for the upgrade check. */
export interface StencilUpgradeRef {
  stencilId: string;
  version: number;
  catalogKey: string;
}

/**
 * Build the stencil-upgrade-check refs for a document.
 *
 * Each *linked* stencil node — one carrying `stencilId`, `version`, AND
 * `catalogKey` — yields one ref. Unlinked stencils (missing any of those
 * props) are skipped, matching the `stencilRef` helper. `catalogKey` is
 * mandatory: the host's `checkUpgrades` callback assembles the
 * `/stencils/{catalogKey}/{stencilId}/versions` URL from it, so dropping it
 * here produced an `undefined` path segment and a silently-empty upgrade check.
 */
export function collectStencilUpgradeRefs(doc: TemplateDocument): StencilUpgradeRef[] {
  return Object.values(doc.nodes)
    .filter(
      (n) => n.type === 'stencil' && n.props?.stencilId && n.props?.version && n.props?.catalogKey,
    )
    .map((n) => ({
      stencilId: n.props!.stencilId as string,
      version: n.props!.version as number,
      catalogKey: n.props!.catalogKey as string,
    }));
}
