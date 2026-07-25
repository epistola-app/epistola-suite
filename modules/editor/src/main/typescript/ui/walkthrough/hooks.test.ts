import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { TOUR_HOOKS } from './hooks.js';

/**
 * Cross-reference guard for the tour-hook vocabulary: every {@link TOUR_HOOKS}
 * value must be stamped as a `data-tour` attribute by a component. Without this,
 * renaming a hooked element silently degrades a chapter — `skipMissingElement`
 * drops the step with no warning, no console output, and no failing test.
 *
 * Consumers (the tours, the runner, hooks.ts itself) are excluded from the scan
 * so a selector string in a tour can never satisfy the producer check.
 */

const WALKTHROUGH_DIR = dirname(fileURLToPath(import.meta.url));
const SRC_ROOT = join(WALKTHROUGH_DIR, '..', '..');

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
    // Inside the walkthrough dir, only the launcher is a producer (it stamps the
    // Guide button's hook); everything else there consumes hooks.
    const rel = relative(WALKTHROUGH_DIR, p);
    const inWalkthrough = !rel.startsWith('..' + sep);
    return !inWalkthrough || rel === 'launcher.ts';
  })
  .map((p) => ({ path: relative(SRC_ROOT, p), text: readFileSync(p, 'utf8') }));

/** Whether some component stamps `data-tour` with this hook value. */
function stampedBy(hook: string): string | undefined {
  // Literal attribute (`data-tour="x"`) or a single-line interpolation whose
  // expression contains the quoted value (`data-tour=${cond ? 'x' : nothing}`).
  const literal = `data-tour="${hook}"`;
  const interpolated = new RegExp(`data-tour=\\$\\{[^}]*['"\`]${hook}['"\`]`);
  const direct = sources.find((s) => s.text.includes(literal) || interpolated.test(s.text));
  if (direct) return direct.path;

  // Dynamic families, e.g. the sidebar's data-tour=${`tab-${tab.id}`}: accept a
  // file that builds the family prefix AND declares the id (`id: 'blocks'`).
  const dash = hook.indexOf('-');
  if (dash < 0) return undefined;
  const family = hook.slice(0, dash);
  const id = hook.slice(dash + 1);
  const familyProducer = 'data-tour=${`' + family + '-${';
  const idDeclaration = new RegExp(`id: '${id}'`);
  return sources.find((s) => s.text.includes(familyProducer) && idDeclaration.test(s.text))?.path;
}

describe('TOUR_HOOKS', () => {
  for (const [key, hook] of Object.entries(TOUR_HOOKS)) {
    it(`"${hook}" (${key}) is stamped by a component`, () => {
      expect(
        stampedBy(hook),
        `no component stamps data-tour="${hook}" — either restore the attribute or remove the hook (and any tour step targeting it)`,
      ).toBeDefined();
    });
  }
});
