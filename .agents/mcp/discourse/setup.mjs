#!/usr/bin/env node
// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import keytar from 'keytar';
import { spawnSync } from 'child_process';
import { mkdtempSync, readFileSync, rmSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';
import { ACCOUNT, SERVICE, SITE, discourseMcpCli } from './config.mjs';

console.log('=== Discourse MCP Setup ===\n');
console.log(`Generates a User API Key for ${SITE} under your own account.`);
console.log('Open the activation URL it prints, enter the code and approve the request.\n');

// The generator can only hand the key over in a file; keep that file private and short-lived.
const dir = mkdtempSync(join(tmpdir(), 'discourse-mcp-'));
const profile = join(dir, 'profile.json');
try {
  const result = spawnSync(
    process.execPath,
    [
      discourseMcpCli(),
      'generate-user-api-key',
      '--site',
      SITE,
      '--scopes',
      'read,write',
      '--save-to',
      profile,
    ],
    { stdio: ['inherit', 'ignore', 'inherit'] },
  );
  if (result.status !== 0) {
    console.error('\n✗ Generating the key failed.');
    process.exit(1);
  }
  const pair = JSON.parse(readFileSync(profile, 'utf8')).auth_pairs?.find((p) => p.site === SITE);
  if (!pair?.user_api_key) {
    console.error('\n✗ No key was returned.');
    process.exit(1);
  }
  await keytar.setPassword(
    SERVICE,
    ACCOUNT,
    JSON.stringify({
      user_api_key: pair.user_api_key,
      user_api_client_id: pair.user_api_client_id,
    }),
  );
} finally {
  rmSync(dir, { recursive: true, force: true });
}

const store = process.platform === 'darwin' ? 'macOS Keychain' : 'OS credential store';
console.log(`✓ Key stored in the ${store}`);
console.log('\n=== Setup complete! ===');
console.log('Restart Claude Code and the Discourse MCP server will be available.');
console.log(`Revoke the key at any time under your profile on ${SITE} → Preferences → Apps.`);
