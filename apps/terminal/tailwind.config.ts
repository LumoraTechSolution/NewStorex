import type { Config } from 'tailwindcss';

const config: Config = {
  content: ['./src/**/*.{ts,tsx}', '../../packages/ui/src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // Identity, not an interactive surface — see packages/ui/src/tokens.css. Always
        // pair with brand-ink; the logo blue fails AA against white.
        brand: {
          DEFAULT: 'var(--lum-brand)',
          ink: 'var(--lum-brand-ink)',
        },
        // accent-ink exists so nothing has to reach for text-white on a coloured button
        // and quietly reintroduce the contrast bug the two tokens were split to avoid.
        accent: {
          DEFAULT: 'var(--lum-accent)',
          ink: 'var(--lum-accent-ink)',
        },
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
