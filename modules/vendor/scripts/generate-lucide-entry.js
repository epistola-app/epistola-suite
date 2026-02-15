#!/usr/bin/env node

/**
 * Scans the editor module for lucide-react imports and generates
 * an entry file with only the used icons.
 *
 * Usage: node scripts/generate-lucide-entry.js
 */

import {readdirSync, readFileSync, statSync, writeFileSync} from 'fs';
import {dirname, join} from 'path';
import {fileURLToPath} from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const SCHEMA_MANAGER_SRC = join(__dirname, '../../schema-manager/src');
const OUTPUT_FILE = join(__dirname, '../entries/lucide.js');

// Recursively find all .tsx and .ts files
function findFiles(dir, files = []) {
  const entries = readdirSync(dir);
  for (const entry of entries) {
    const fullPath = join(dir, entry);
    const stat = statSync(fullPath);
    if (stat.isDirectory()) {
      findFiles(fullPath, files);
    } else if (entry.endsWith('.tsx') || entry.endsWith('.ts')) {
      files.push(fullPath);
    }
  }
  return files;
}

// Extract icon names from lucide-react imports
function extractIcons(content) {
  const icons = new Set();
  // Match: import { Icon1, Icon2 } from 'lucide-react' or "lucide-react"
  const regex = /import\s*\{([^}]+)\}\s*from\s*['"]lucide-react['"]/g;
  let match;
  while ((match = regex.exec(content)) !== null) {
    const imports = match[1].split(',').map(s => s.trim()).filter(Boolean);
    for (const icon of imports) {
      // Handle "Icon as Alias" syntax
      const iconName = icon.split(/\s+as\s+/)[0].trim();
      if (iconName) {
        icons.add(iconName);
      }
    }
  }
  return icons;
}

// Main
function main() {
  console.log('Scanning for lucide-react imports...');

  const schemaManagerFiles = findFiles(SCHEMA_MANAGER_SRC);
  const allFiles = [...schemaManagerFiles];
  const allIcons = new Set();

  for (const file of allFiles) {
    const content = readFileSync(file, 'utf-8');
    const icons = extractIcons(content);
    for (const icon of icons) {
      allIcons.add(icon);
    }
  }

  const sortedIcons = Array.from(allIcons).sort();

  if (sortedIcons.length === 0) {
    console.log('No lucide-react icons found.');
    const output = `// Auto-generated - do not edit manually
// Run: pnpm --filter @epistola/vendor generate:lucide
// No icons found
export {};
`;
    writeFileSync(OUTPUT_FILE, output);
    return;
  }

  console.log('Found ' + sortedIcons.length + ' icons: ' + sortedIcons.join(', '));

  const output = `// Auto-generated - do not edit manually
// Run: pnpm --filter @epistola/vendor generate:lucide
export {
  ${sortedIcons.join(',\n  ')}
} from 'lucide-react';
`;

  writeFileSync(OUTPUT_FILE, output);
  console.log('Generated ' + OUTPUT_FILE);
}

main();
