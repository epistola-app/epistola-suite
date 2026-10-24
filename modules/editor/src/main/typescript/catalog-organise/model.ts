// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

export interface OrganiseCatalog {
  key: string;
  name: string;
  /** Whether the catalog can be changed here (ADR 0032): only such a catalog takes a move. */
  editable: boolean;
}

export interface OrganiseResource {
  /** `<type>:<namespace>/<catalog>/<key>` — also the deep-link form. */
  id: string;
  type: string;
  /** The catalog's address, `<namespace>/<catalog>` (ADR 0034), as {@link OrganiseCatalog.key} names it. */
  catalogKey: string;
  key: string;
  name: string;
  catalogName: string;
  /** Why moving this has a consequence worth knowing, or absent when unremarkable. */
  note?: string | null;
}

/** A destination chosen for one selected resource. Empty fields mean "unchanged". */
/**
 * Where one selected resource is going.
 *
 * `overridden` is what separates "this row follows the shared destination" from "this row was
 * deliberately given its own": a row that merely happens to match the shared catalog still follows
 * it, so changing the shared destination moves it too.
 */
export interface Destination {
  catalog: string;
  key: string;
  overridden: boolean;
}

/** A resource as a plan names it: its catalog by key, and by address, `<namespace>/<catalog>`. */
export interface PlannedAddress {
  type: string;
  catalogKey: string;
  catalogAddress: string;
  key: string;
}

export interface RelocationPlan {
  source: PlannedAddress;
  target: PlannedAddress;
  mutableRewriteCount: number;
  immutableReferenceCount: number;
}

export interface Blocker {
  code: string;
  message: string;
  source?: { type: string; catalogKey: string; key: string };
  /** The blocked resource as `<type>:<namespace>/<catalog>/<key>`, the same form as {@link OrganiseResource.id}. */
  sourceId?: string | null;
}

/** Surfaced before applying, but does not stop the move. */
export interface Warning {
  code: string;
  message: string;
  source?: { type: string; catalogKey: string; key: string };
  sourceId?: string | null;
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
