import { defineConfig } from 'vitest/config';
import { fileURLToPath } from 'node:url';

export default defineConfig({
  test: {
    include: ['src/**/*.test.ts'],
  },
  resolve: {
    // Mirrors tsconfig's `@/*` path mapping. Vitest does not read tsconfig paths on its own, and
    // without this every import in a test resolves to nothing.
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
});
