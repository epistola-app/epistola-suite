#!/usr/bin/env node

/**
 * Generate an SVG sprite sheet from lucide-static icon files.
 *
 * Each icon becomes a <symbol> element with id="icon-{name}".
 * Usage in HTML: <svg class="ep-icon"><use href="/design-system/icons.svg#icon-file-text"/></svg>
 *
 * Run: pnpm --filter @epistola/design-system generate:icons
 */

import { readFileSync, writeFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, "..");

// Use createRequire to resolve from the design-system package context
// This works correctly with pnpm's virtual store structure
const require = createRequire(join(ROOT, "package.json"));
const lucideDir = join(dirname(require.resolve("lucide-static/package.json")), "icons");

const ICONS = [
  "file-text",
  "palette",
  "zap",
  "globe",
  "plus",
  "trash-2",
  "pencil",
  "eye",
  "search",
  "chevron-right",
  "x",
  "check-circle",
  "alert-circle",
  "alert-triangle",
  "info",
  "arrow-left",
  "external-link",
  "play",
  "ban",
  "building-2",
  "log-in",
  "log-out",
  "undo-2",
  "redo-2",
  "save",
  "clock",
  "gauge",
  "activity",
  "settings",
  "layout-template",
  "copy",
];

function extractSvgContent(filePath) {
  const raw = readFileSync(filePath, "utf-8");
  const viewBoxMatch = raw.match(/viewBox="([^"]+)"/);
  const viewBox = viewBoxMatch ? viewBoxMatch[1] : "0 0 24 24";

  // Extract everything between <svg...> and </svg>
  const innerMatch = raw.match(/<svg[^>]*>([\s\S]*?)<\/svg>/);
  const inner = innerMatch ? innerMatch[1].trim() : "";

  return { viewBox, inner };
}

function generateSprite() {
  const symbols = [];

  for (const name of ICONS) {
    const filePath = join(lucideDir, `${name}.svg`);
    try {
      const { viewBox, inner } = extractSvgContent(filePath);
      symbols.push(
        `  <symbol id="icon-${name}" viewBox="${viewBox}" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">\n    ${inner}\n  </symbol>`,
      );
    } catch {
      console.warn(`Warning: Icon "${name}" not found at ${filePath}, skipping`);
    }
  }

  const sprite = `<svg xmlns="http://www.w3.org/2000/svg" style="display:none">\n${symbols.join("\n")}\n</svg>\n`;

  const outPath = join(ROOT, "icons.svg");
  writeFileSync(outPath, sprite, "utf-8");
  console.log(`Generated ${outPath} with ${symbols.length} icons`);
}

generateSprite();
