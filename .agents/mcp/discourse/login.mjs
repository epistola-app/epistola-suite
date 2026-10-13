// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import keytar from 'keytar';
import { execFile } from 'child_process';
import { generateKeyPairSync, randomBytes } from 'crypto';
import { createRequire } from 'module';
import { dirname, join } from 'path';
import { ACCOUNT, SERVICE, SITE } from './config.mjs';

const CLIENT_ID = 'epistola-suite-agent';

/** Discourse's own device-login helpers, from the pinned @discourse/mcp. */
async function generator() {
  const require = createRequire(import.meta.url);
  const root = dirname(require.resolve('@discourse/mcp/package.json'));
  return import(join(root, 'dist', 'user-api-key-generator.js'));
}

function openInBrowser(url) {
  const [cmd, args] =
    process.platform === 'darwin'
      ? ['open', [url]]
      : process.platform === 'win32'
        ? ['cmd', ['/c', 'start', '', url]]
        : ['xdg-open', [url]];
  execFile(cmd, args, () => {});
}

function notify(message) {
  if (process.platform !== 'darwin') return;
  execFile(
    'osascript',
    ['-e', `display notification ${JSON.stringify(message)} with title "Epistola Community"`],
    () => {},
  );
}

/** The stored key, or null when none is stored. */
export async function storedKey() {
  const stored = await keytar.getPassword(SERVICE, ACCOUNT);
  return stored ? JSON.parse(stored) : null;
}

/** The forum account a key signs in as, or null when the forum refuses the key. */
export async function accountOf(key) {
  const response = await fetch(`${SITE}/session/current.json`, {
    headers: {
      Accept: 'application/json',
      'User-Api-Key': key.user_api_key,
      'User-Api-Client-Id': key.user_api_client_id,
    },
  });
  if (!response.ok) return null;
  return (await response.json()).current_user?.username ?? null;
}

/**
 * Signs in through Discourse's device login: opens the approval page in the browser, waits for the
 * approval and stores the resulting User API Key. The account is whichever one the browser is
 * signed in with.
 */
export async function login(log = console.error) {
  const gen = await generator();
  const { publicKey, privateKey } = generateKeyPairSync('rsa', {
    modulusLength: 2048,
    publicKeyEncoding: { type: 'spki', format: 'pem' },
    privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
  });
  const nonce = randomBytes(32).toString('hex');
  const authorization = await gen.createDeviceAuthorization(
    {
      site: SITE,
      applicationName: 'Epistola coding agent',
      clientId: CLIENT_ID,
      scopes: 'read,write',
    },
    publicKey,
    nonce,
  );
  const url = authorization.verificationUriWithRequest ?? authorization.verificationUri;
  log(`Approve access to ${SITE} in your browser: ${url} (code ${authorization.userCode})`);
  openInBrowser(url);
  notify(`Approve access in your browser. Code: ${authorization.userCode}`);

  const payload = await gen.pollDeviceAuthorization(SITE, authorization);
  const result = gen.decryptPayload(payload, privateKey, 'oaep');
  if (result.nonce !== nonce) throw new Error('The approval did not match this request');

  const key = { user_api_key: result.key, user_api_client_id: CLIENT_ID };
  await keytar.setPassword(SERVICE, ACCOUNT, JSON.stringify(key));
  return key;
}
