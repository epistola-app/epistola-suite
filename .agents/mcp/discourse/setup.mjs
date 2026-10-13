#!/usr/bin/env node
// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// Signs in again, replacing the stored key: to switch accounts, or after revoking the key.
// Not needed for a first start: the MCP server signs in by itself.
import { SITE } from './config.mjs';
import { accountOf, login } from './login.mjs';

console.log(
  `Sign in to ${SITE} with the account the agent should use, then approve the request.\n`,
);
const key = await login(console.log);
console.log(`\n✓ Signed in as ${await accountOf(key)}. Restart the agent to use this account.`);
