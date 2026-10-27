// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * A save into a catalog version that was released while it was open is refused with a 409
 * `catalog-version-released` (ADR 0036): a released version never changes, and work goes on in the
 * catalog's next draft, which the same page without a version opens. The template editor, its
 * stencil saves and the theme editor share this.
 */

/** Whether a response is the server saying the version being saved into has been released. */
export function isReleasedVersionProblem(status: number, type: string | null | undefined): boolean {
  return status === 409 && typeof type === 'string' && type.endsWith('/catalog-version-released');
}

/**
 * The same page in the catalog's next draft: the catalog address without its version
 * (`/catalogs/{namespace}/{key}@{version}/…` becomes `/catalogs/{namespace}/{key}/…`, and the short
 * `/catalogs/{key}@{version}/…` becomes `/catalogs/{key}/…`).
 */
export function nextDraftLocation(location: { pathname: string; search: string }): string {
  const path = location.pathname.replace(/(\/catalogs\/(?:[^/@]+\/)?[^/@]+)@[^/]+/, '$1');
  return path + location.search;
}

/**
 * Asks whether to open the next draft, and opens it when the author agrees.
 *
 * @returns true when the page is navigating away, so the caller stops reporting the failure.
 */
export function offerNextDraft(
  message: string,
  win: Pick<Window, 'confirm'> & {
    location: Pick<Location, 'pathname' | 'search' | 'assign'>;
  } = window,
): boolean {
  const open = win.confirm(
    message + '\n\nOK: open the next draft (your unsaved changes here are lost).',
  );
  if (open) win.location.assign(nextDraftLocation(win.location));
  return open;
}
