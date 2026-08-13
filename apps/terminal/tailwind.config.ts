import type { Config } from 'tailwindcss';

const config: Config = {
  content: ['./src/**/*.{ts,tsx}', '../../packages/ui/src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        accent: 'var(--lum-accent)',
        ok: 'var(--lum-ok)',
        danger: 'var(--lum-danger)',
        pending: 'var(--lum-pending)',
        page: 'var(--lum-page)',
        surface: 'var(--lum-surface)',
        ink: {
          DEFAULT: 'var(--lum-ink)',
          2: 'var(--lum-ink-2)',
          3: 'var(--lum-ink-3)',
        },
        hair: 'var(--lum-hair)',
      },
      fontFamily: {
        sans: 'var(--lum-font-sans)',
        mono: 'var(--lum-font-mono)',
      },
      spacing: {
        // Minimum interactive target on the terminal. Fingers, fast, sometimes gloved.
        touch: 'var(--lum-touch-min)',
      },
    },
  },
  plugins: [],
};

export default config;
