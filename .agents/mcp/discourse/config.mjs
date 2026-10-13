// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { createRequire } from 'module';
import { dirname, join } from 'path';

export const SITE = 'https://community.epistola.app';
export const SERVICE = 'epistola-discourse-mcp';
export const ACCOUNT = 'user-api-key';

/** The CLI of the pinned @discourse/mcp, so pnpm's lockfile and supply-chain gate cover it. */
export function discourseMcpCli() {
  const require = createRequire(import.meta.url);
  return join(dirname(require.resolve('@discourse/mcp/package.json')), 'dist', 'index.js');
}
