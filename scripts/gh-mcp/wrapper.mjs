#!/usr/bin/env node
import keytar from 'keytar';
import {spawn} from 'child_process';

const SERVICE = 'epistola-github-mcp';
const ACCOUNT = 'token';

const token = await keytar.getPassword(SERVICE, ACCOUNT);

if (!token) {
  console.error('ERROR: GitHub MCP token not configured.');
  console.error('Run: pnpm run setup:github-mcp');
  process.exit(1);
}

// Launch MCP server with token
const child = spawn('npx', ['-y', '@modelcontextprotocol/server-github'], {
  env: { ...process.env, GITHUB_PERSONAL_ACCESS_TOKEN: token },
  stdio: 'inherit',
  shell: process.platform === 'win32',
});

child.on('exit', (code) => process.exit(code ?? 0));
