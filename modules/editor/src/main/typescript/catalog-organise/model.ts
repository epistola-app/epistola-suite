// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

export interface OrganiseCatalog {
  key: string;
  name: string;
  type: string;
}

export interface OrganiseResource {
  /** `<type>:<catalog>:<key>` — also the deep-link form. */
  id: string;
  type: string;
  catalogKey: string;
  key: string;
  name: string;
  catalogName: string;
  /** Why moving this has a consequence worth knowing, or absent when unremarkable. */
  note?: string | null;
}

/** A destination chosen for one selected resource. Empty fields mean "unchanged". */
export interface Destination {
  catalog: string;
  key: string;
}

export interface RelocationPlan {
  source: { type: string; catalogKey: string; key: string };
  target: { type: string; catalogKey: string; key: string };
  mutableRewriteCount: number;
  immutableReferenceCount: number;
}

export interface Blocker {
  code: string;
  message: string;
  source?: { type: string; catalogKey: string; key: string };
}

/** Surfaced before applying, but does not stop the move. */
export interface Warning {
  code: string;
  message: string;
  source?: { type: string; catalogKey: string; key: string };
}

export interface RelocationPreview {
  relocations: RelocationPlan[];
  mutableRewriteCount: number;
  immutableReferenceCount: number;
  blockers: Blocker[];
  warnings: Warning[];
  planFingerprint: string;
  executable: boolean;
}
