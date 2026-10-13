#!/usr/bin/env node
// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import keytar from 'keytar';
import { spawn } from 'child_process';
import { ACCOUNT, SERVICE, SITE, discourseMcpCli } from './config.mjs';

const stored = await keytar.getPassword(SERVICE, ACCOUNT);

if (!stored) {
  console.error('ERROR: Discourse MCP key not configured.');
  console.error('Run: pnpm run setup:discourse-mcp');
  process.exit(1);
}

const profile = JSON.stringify({ auth_pairs: [{ site: SITE, ...JSON.parse(stored) }] });

// The key reaches the server through a pipe on fd 3, so it is never on disk or in the
// process list, as a file or an --auth_pairs argument would be.
const child = spawn(
  process.execPath,
  [
    discourseMcpCli(),
    '--site',
    SITE,
    '--profile',
    '/dev/fd/3',
    '--allow_writes',
    '--tools_mode',
    'discourse_api_only',
  ],
  { stdio: ['inherit', 'inherit', 'inherit', 'pipe'] },
);
child.stdio[3].end(profile);

child.on('exit', (code) => process.exit(code ?? 0));
