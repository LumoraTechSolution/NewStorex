import type { Metadata, Viewport } from 'next';

import './globals.css';

export const metadata: Metadata = {
  title: 'StoreX Terminal',
  description: 'Offline-first point of sale',
};

export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  maximumScale: 1,
  userScalable: false,
};

/**
 * Applies the till's saved theme before the first paint (D6).
 *
 * Runs synchronously ahead of any markup below it, because the alternative — setting the
 * attribute in an effect after hydration — means a shop that chose light mode sees a
 * black flash on every launch and every reload. On an appliance that is opened at the
 * start of a shift and never closed that is a small thing; on one that reloads after an
 * update it looks broken.
 *
 * Reads localStorage rather than the OS: a shop PC's system theme is whatever the person
 * who installed Windows left it on, and the till changing colour after a system update
 * would be a support call. The choice is explicit and it persists.
 */
const APPLY_SAVED_THEME = `
try {
  if (localStorage.getItem('storex.terminal.theme') === 'light') {
    document.documentElement.dataset.theme = 'light';
  }
} catch (e) {}
`;

export default function RootLayout({ children }: { children: React.ReactNode }) {
  // data-surface pins the appliance palette. The terminal does not follow the OS theme;
  // it defaults to dark and stays there unless the shopkeeper explicitly chooses light.
  return (
    <html lang="en" data-surface="terminal" suppressHydrationWarning>
      <body>
        <script dangerouslySetInnerHTML={{ __html: APPLY_SAVED_THEME }} />
        {children}
      </body>
    </html>
  );
}
