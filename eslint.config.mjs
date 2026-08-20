import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { FlatCompat } from '@eslint/eslintrc';
import js from '@eslint/js';
import prettier from 'eslint-config-prettier';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';

const compat = new FlatCompat({ baseDirectory: dirname(fileURLToPath(import.meta.url)) });

export default tseslint.config(
  {
    ignores: [
      '**/node_modules/**',
      '**/dist/**',
      '**/.next/**',
      '**/.turbo/**',
      '**/coverage/**',
      'services/backend/**',
    ],
  },

  js.configs.recommended,
  ...tseslint.configs.recommended,

  {
    languageOptions: {
      globals: { ...globals.node, ...globals.es2022 },
    },
    rules: {
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
      '@typescript-eslint/consistent-type-imports': ['error', { prefer: 'type-imports' }],
      eqeqeq: ['error', 'always'],
      'no-console': ['warn', { allow: ['warn', 'error'] }],
    },
  },

  // Ground rule (ROADMAP §A): money is integer minor units. Floating-point parsing has no
  // place in the domain package — a rupee lost to binary rounding is invisible until it isn't.
  {
    files: ['packages/domain/**/*.ts'],
    rules: {
      'no-restricted-globals': [
        'error',
        { name: 'parseFloat', message: 'Money is integer minor units. Do not parse floats.' },
      ],
      'no-restricted-properties': [
        'error',
        {
          object: 'Number',
          property: 'parseFloat',
          message: 'Money is integer minor units. Do not parse floats.',
        },
      ],
    },
  },

  // Next.js apps: browser globals, React hook rules, and Next's own core-web-vitals set.
  {
    files: ['apps/**/*.{ts,tsx}'],
    languageOptions: {
      globals: { ...globals.browser },
    },
    plugins: { 'react-hooks': reactHooks },
    rules: reactHooks.configs.recommended.rules,
  },
  // eslint-config-next runs a major ahead of next@14.2 on purpose: the 14.x plugin calls
  // context.getAncestors(), which ESLint 9 removed. The plugin lints code patterns, not the
  // Next runtime, so the version skew is safe.
  ...compat.extends('next/core-web-vitals').map((config) => ({
    ...config,
    files: ['apps/**/*.{ts,tsx}'],
  })),
  {
    files: ['apps/**/*.{ts,tsx}'],
    rules: {
      // Both apps are app-router only; this rule looks for a pages/ directory.
      '@next/next/no-html-link-for-pages': 'off',
    },
  },

  // Playwright e2e specs live under apps/ but are Node test code, not React.
  //
  // Two rules misfire there, both because Playwright's fixture API happens to collide with
  // React's vocabulary. `use(...)` is how a fixture hands its value to the test; the
  // react-hooks plugin sees a call to something named `use` — React 19's hook — outside a
  // component and objects. And a fixture that needs no other fixtures is declared
  // `async ({}, use) =>`, which is an empty destructuring pattern by design: it is
  // Playwright's own documented signature, not a mistake to tidy up.
  {
    files: ['apps/**/e2e/**/*.ts'],
    languageOptions: { globals: { ...globals.node } },
    rules: {
      'react-hooks/rules-of-hooks': 'off',
      'no-empty-pattern': 'off',
    },
  },

  // Config files run in Node and are allowed to be CommonJS-ish.
  {
    files: ['**/*.config.{js,mjs,ts}', '**/*.cjs'],
    rules: { '@typescript-eslint/no-require-imports': 'off' },
  },

  prettier,
);
