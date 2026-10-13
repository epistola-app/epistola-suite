#!/usr/bin/env node
// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { spawn } from 'child_process';
import { SITE, discourseMcpCli } from './config.mjs';
import { accountOf, login, storedKey } from './login.mjs';

// Without a working key, sign in first: the browser opens on the forum's approval page.
let key = await storedKey();
let account = key && (await accountOf(key));
if (!account) {
  try {
    key = await login();
    account = await accountOf(key);
  } catch (error) {
    console.error(`ERROR: signing in to ${SITE} failed: ${error.message}`);
    process.exit(1);
  }
}
console.error(`Signed in to ${SITE} as ${account}`);

const profile = JSON.stringify({ auth_pairs: [{ site: SITE, ...key }] });

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
