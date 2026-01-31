#!/usr/bin/env node
import keytar from 'keytar';
import {execSync} from 'child_process';
import readline from 'readline';

const SERVICE = 'epistola-github-mcp';
const ACCOUNT = 'token';

console.log('=== GitHub MCP Token Setup ===\n');
console.log('Opening GitHub to create a fine-grained PAT...\n');
console.log('Configure with these settings:');
console.log('  - Token name: epistola-mcp');
console.log('  - Expiration: 90 days (recommended)');
console.log('  - Resource owner: epistola-app');
console.log('  - Repository access: Only "epistola-suite"');
console.log('  - Permissions:');
console.log('      - Issues: Read and write');
console.log('      - Pull requests: Read and write');
console.log('      - Projects: Read and write');
console.log('      - Metadata: Read (auto-selected)\n');

// Open browser (cross-platform)
const url = 'https://github.com/settings/personal-access-tokens/new';
const cmd =
  process.platform === 'win32'
    ? `start "" "${url}"`
    : process.platform === 'darwin'
      ? `open "${url}"`
      : `xdg-open "${url}"`;

try {
  execSync(cmd, { stdio: 'ignore' });
} catch {
  console.log(`Please open manually: ${url}\n`);
}

// Read token from user
const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
const token = await new Promise((resolve) => rl.question('Paste your token here: ', resolve));
rl.close();

if (!token || !token.startsWith('github_pat_')) {
  console.error('\n✗ Invalid token format. Fine-grained PATs start with "github_pat_"');
  process.exit(1);
}

// Validate token with GitHub API
console.log('\nValidating token...');
try {
  execSync(`gh api user -H "Authorization: Bearer ${token}"`, { stdio: 'ignore' });
  console.log('✓ Token is valid');
} catch {
  console.error('✗ Token validation failed. Please check the token and try again.');
  process.exit(1);
}

// Store in OS credential manager
await keytar.setPassword(SERVICE, ACCOUNT, token);
const store = process.platform === 'darwin' ? 'macOS Keychain' : 'Windows Credential Vault';
console.log(`✓ Token stored securely in ${store}`);
console.log('\n=== Setup complete! ===');
console.log('Restart Claude Code and the GitHub MCP server will be available.');
