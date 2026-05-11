export default {
  ignoreFiles: ['**/dist/**', '**/build/**', '**/bin/**', '**/coverage/**'],
  rules: {
    'selector-disallowed-list': [
      ['.btn', /^\.btn-/],
      {
        severity: 'error',
        message: 'Use ep-btn-* prefixed classes instead',
      },
    ],
  },
};
