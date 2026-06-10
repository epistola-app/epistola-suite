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
  overrides: [
    {
      files: ['modules/design-system/base.css', 'modules/design-system/components.css'],
      rules: {
        'color-no-hex': [
          true,
          {
            severity: 'error',
            message:
              'Use an --ep-* color token instead of %s (see modules/design-system/tokens.css)',
          },
        ],
        'function-disallowed-list': [
          [
            'rgb',
            'rgba',
            'hsl',
            'hsla',
            'hwb',
            'lab',
            'lch',
            'oklab',
            'oklch',
            'color',
            'color-mix',
          ],
          {
            severity: 'error',
            message:
              'Use an --ep-* color token instead of %s() (see modules/design-system/tokens.css)',
          },
        ],
        'unit-disallowed-list': [
          [
            'px',
            'em',
            'rem',
            'pt',
            'cm',
            'mm',
            'q',
            'in',
            'pc',
            'ch',
            'ex',
            'lh',
            'rlh',
            'vw',
            'vh',
            'vmin',
            'vmax',
            'svw',
            'svh',
            'lvw',
            'lvh',
            'dvw',
            'dvh',
            'vb',
            'vi',
            'cqw',
            'cqh',
            'cqi',
            'cqb',
            'cqmin',
            'cqmax',
          ],
          {
            severity: 'error',
            message:
              'Use an --ep-* token instead of "%s" units (see modules/design-system/tokens.css)',
          },
        ],
      },
    },
  ],
};
