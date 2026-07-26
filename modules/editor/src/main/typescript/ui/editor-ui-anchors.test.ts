// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { EDITOR_UI_ANCHORS } from './editor-ui-anchors.js';
import { createDefaultRegistry } from '../engine/registry.js';

/**
 * Stamping guard for the editor-anchor vocabulary: components import {@link EDITOR_UI_ANCHORS}
 * and bind `data-editor-anchor=${EDITOR_UI_ANCHORS.x}`, so agreement on the *name* is compile-checked
 * — but only this test proves each anchor is actually *stamped* by a component. Without
 * it, deleting a stamped attribute (or adding an anchor nobody stamps) leaves an extension
 * step that `skipMissingElement` silently drops, with no warning and no failure.
 *
 * Consumers (the tours, the runner, editor-ui-anchors.ts itself) are excluded from the scan
 * so a literal `[data-editor-anchor~="…"]` selector in a tour can never satisfy the check.
 */

const UI_DIR = dirname(fileURLToPath(import.meta.url));
const SRC_ROOT = join(UI_DIR, '..');
const WALKTHROUGH_DIR = join(SRC_ROOT, 'plugins', 'walkthrough');

function walk(dir: string, out: string[] = []): string[] {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name);
    if (entry.isDirectory()) walk(p, out);
    else if (p.endsWith('.ts') && !p.endsWith('.test.ts')) out.push(p);
  }
  return out;
}

const sources = walk(SRC_ROOT)
  .filter((p) => {
    if (p === join(UI_DIR, 'editor-ui-anchors.ts')) return false;
    // Walkthrough code consumes editor anchors but never produces them.
    const rel = relative(WALKTHROUGH_DIR, p);
    const inWalkthrough = !rel.startsWith('..' + sep);
    return !inWalkthrough;
  })
  .map((p) => ({ path: relative(SRC_ROOT, p), text: readFileSync(p, 'utf8') }));

/**
 * Dynamic anchor families stamped as `data-editor-anchor=${`<family>-${…}`}`, where the member
 * ids are not literal in the producer file. Each family declares how to prove the
 * id half: the sidebar declares its tab ids literally; the palette's items are the
 * registered block types, so the id is checked against the real component registry.
 */
const FAMILIES: Record<string, (id: string, fileText: string) => boolean> = {
  tab: (id, fileText) => fileText.includes(`id: '${id}'`),
  'palette-item': (id) => createDefaultRegistry().get(id) !== undefined,
};

/** Whether some component stamps `data-editor-anchor` with this anchor. */
function stampedBy(key: string, anchor: string): string | undefined {
  // A binding referencing the table before the element's closing `>` — covers plain,
  // ternary, composite word-list, and formatter-wrapped multiline bindings.
  const byKey = new RegExp(`data-editor-anchor=[^>]*EDITOR_UI_ANCHORS\\.${key}\\b`);
  const direct = sources.find((s) => byKey.test(s.text));
  if (direct) return direct.path;

  // Dynamic families: the file must build the family's `data-editor-anchor` prefix, and
  // the family's own evidence must vouch for the id.
  for (const [family, hasId] of Object.entries(FAMILIES)) {
    const prefix = family + '-';
    if (!anchor.startsWith(prefix)) continue;
    const id = anchor.slice(prefix.length);
    const producer = 'data-editor-anchor=${`' + family + '-${';
    const file = sources.find((s) => s.text.includes(producer) && hasId(id, s.text));
    if (file) return file.path;
  }
  return undefined;
}

describe('EDITOR_UI_ANCHORS', () => {
  for (const [key, anchor] of Object.entries(EDITOR_UI_ANCHORS)) {
    it(`"${anchor}" (${key}) is stamped by a component`, () => {
      expect(
        stampedBy(key, anchor),
        `no component stamps data-editor-anchor for EDITOR_UI_ANCHORS.${key} ("${anchor}") — either restore the binding or remove the anchor`,
      ).toBeDefined();
    });
  }
});
