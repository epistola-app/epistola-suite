// @vitest-environment happy-dom
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { TOUR_HOOKS } from './tour-hooks.js';
import { createDefaultRegistry } from '../engine/registry.js';

/**
 * Stamping guard for the tour-hook vocabulary: components import {@link TOUR_HOOKS}
 * and bind `data-tour=${TOUR_HOOKS.x}`, so agreement on the *name* is compile-checked
 * — but only this test proves each hook is actually *stamped* by a component. Without
 * it, deleting a stamped attribute (or adding a hook nobody stamps) leaves a tour
 * step that `skipMissingElement` silently drops, with no warning and no failure.
 *
 * Consumers (the tours, the runner, tour-hooks.ts itself) are excluded from the scan
 * so a `tourHook(TOUR_HOOKS.x)` selector in a tour can never satisfy the check.
 */

const UI_DIR = dirname(fileURLToPath(import.meta.url));
const SRC_ROOT = join(UI_DIR, '..');
const WALKTHROUGH_DIR = join(UI_DIR, 'walkthrough');

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
    if (p === join(UI_DIR, 'tour-hooks.ts')) return false;
    // Inside the walkthrough dir, only the launcher is a producer (it stamps the
    // Guide button's hook); everything else there consumes hooks.
    const rel = relative(WALKTHROUGH_DIR, p);
    const inWalkthrough = !rel.startsWith('..' + sep);
    return !inWalkthrough || rel === 'launcher.ts';
  })
  .map((p) => ({ path: relative(SRC_ROOT, p), text: readFileSync(p, 'utf8') }));

/**
 * Dynamic hook families stamped as `data-tour=${`<family>-${…}`}`, where the member
 * ids are not literal in the producer file. Each family declares how to prove the
 * id half: the sidebar declares its tab ids literally; the palette's items are the
 * registered block types, so the id is checked against the real component registry.
 */
const FAMILIES: Record<string, (id: string, fileText: string) => boolean> = {
  tab: (id, fileText) => fileText.includes(`id: '${id}'`),
  'palette-item': (id) => createDefaultRegistry().get(id) !== undefined,
};

/** Whether some component stamps `data-tour` with this hook. */
function stampedBy(key: string, hook: string): string | undefined {
  // A binding referencing the table on a `data-tour=` line — covers plain
  // (`data-tour=${TOUR_HOOKS.x}`), ternary, and composite word-list bindings
  // (`data-tour="${TOUR_HOOKS.a}${cond ? \` ${TOUR_HOOKS.b}\` : ''}"`).
  const byKey = new RegExp(`data-tour=[^\\n]*TOUR_HOOKS\\.${key}\\b`);
  const direct = sources.find((s) => byKey.test(s.text));
  if (direct) return direct.path;

  // Dynamic families: the file must build the family's `data-tour` prefix, and
  // the family's own evidence must vouch for the id.
  for (const [family, hasId] of Object.entries(FAMILIES)) {
    const prefix = family + '-';
    if (!hook.startsWith(prefix)) continue;
    const id = hook.slice(prefix.length);
    const producer = 'data-tour=${`' + family + '-${';
    const file = sources.find((s) => s.text.includes(producer) && hasId(id, s.text));
    if (file) return file.path;
  }
  return undefined;
}

describe('TOUR_HOOKS', () => {
  for (const [key, hook] of Object.entries(TOUR_HOOKS)) {
    it(`"${hook}" (${key}) is stamped by a component`, () => {
      expect(
        stampedBy(key, hook),
        `no component stamps data-tour for TOUR_HOOKS.${key} ("${hook}") — either restore the binding or remove the hook (and any tour step targeting it)`,
      ).toBeDefined();
    });
  }
});
