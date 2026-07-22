#!/usr/bin/env node
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

function readJson(path) {
  return JSON.parse(readFileSync(resolve(path), 'utf8'));
}

const dumpedPath = 'dist/component-registry.json';
const contractPath = 'node_modules/@epistola.app/epistola-model/registry/component-registry.json';

const dumped = readJson(dumpedPath);
const contract = readJson(contractPath);

const dumpedJson = JSON.stringify(dumped, null, 2);
const contractJson = JSON.stringify(contract, null, 2);

if (dumpedJson !== contractJson) {
  console.error(`error: editor runtime registry projection differs from ${contractPath}`);
  console.error(
    `Run pnpm --filter @epistola/editor dump-registry and update epistola-contract's registry.`,
  );
  process.exit(1);
}

console.log(`Registry projection matches ${contractPath}`);
