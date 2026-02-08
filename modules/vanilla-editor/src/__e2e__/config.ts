/**
 * E2E Test Configuration
 *
 * Single source of truth for auth, routes, selectors, block types, and timeouts.
 */

export const AUTH = {
  username: 'admin@local',
  password: 'admin',
  baseUrl: 'http://localhost:4000',
} as const;

export const ROUTES = {
  login: '/login',
  editor: '/tenants/demo-tenant/templates/demo-offer-letter/variants/demo-offer-letter-default/editor',
} as const;

export const SELECTORS = {
  // Auth
  usernameInput: '#username',
  passwordInput: '#password',
  submitButton: 'button[type="submit"]',

  // Editor
  editorRoot: '#editor-root',
  editorContainer: '#editor-container',

  // Toolbar
  addBlockButton: (type: string) => `button[data-action="editor#addBlock"][data-block-type="${type}"]`,
  addToSelectedButton: 'button[data-action="editor#addBlockToSelected"]',

  // Actions
  undoButton: 'button[data-action="editor#undo"]',
  redoButton: 'button[data-action="editor#redo"]',
  deleteButton: 'button[title="Delete block"]',

  // Blocks
  block: '[data-block-id]',
  blockByType: (type: string) => `[data-block-type="${type}"]`,
  blockHeader: '[data-testid="block-header"]',

  // Containers / Nested
  sortableContainer: '.sortable-container',
  emptyState: '.empty-state',
  dragHandle: '.drag-handle',

  // Specific block elements
  expressionInput: '.expression-editor-input',
  columnWrapper: '.column-wrapper',
  tableRow: 'tr',
  addTextLink: 'text=+ Add text',
} as const;

export const BLOCK_TYPES = {
  text: 'text',
  container: 'container',
  conditional: 'conditional',
  loop: 'loop',
  columns: 'columns',
  table: 'table',
  pagebreak: 'pagebreak',
  pageheader: 'pageheader',
  pagefooter: 'pagefooter',
} as const;

export const TIMEOUTS = {
  short: 500,
  medium: 1000,
  long: 5000,
  veryLong: 10000,
} as const;
