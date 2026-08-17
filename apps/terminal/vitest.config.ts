import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    // Two trees: src/ (the renderer's TS) and electron/ (main-process CommonJS — the printer
    // transport). The transport's tests are .test.js (ESM, vitest's requirement) even though
    // the module under test is .cjs (Node/Electron's requirement for main-process code) —
    // importing a CJS module from an ESM test file works fine; the reverse does not.
    include: ['src/**/*.test.ts', 'electron/**/*.test.js'],
    coverage: {
      provider: 'v8',
      include: ['src/**/*.ts', 'electron/**/*.cjs'],
      exclude: ['src/**/*.test.ts', 'electron/**/*.test.js'],
    },
  },
});
