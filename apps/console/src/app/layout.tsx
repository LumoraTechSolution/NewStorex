import type { Metadata, Viewport } from 'next';

import { beforeFirstPaintScript, PAGE_COLOUR } from '@/lib/theme';

import './globals.css';

export const metadata: Metadata = {
  title: 'StoreX Console',
  description: 'Owner console — read-only view of your shop from anywhere',
  // What makes "add to home screen" work. There is deliberately no service worker: caching a
  // shop's takings on a phone would mean showing figures with no way to say how old they are,
  // and this screen's whole credibility rests on the sync time beside the number.
  manifest: '/manifest.webmanifest',
  icons: { icon: '/icon.svg', apple: '/icon.svg' },
  appleWebApp: { capable: true, title: 'StoreX', statusBarStyle: 'black-translucent' },
};

export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  // The status bar of an installed PWA, and it has to be the colour of the page under it or
  // there is a visible seam across the top of the screen. Taken from the design tokens rather
  // than typed again: these read '#FFFFFF' and '#04121C' until M4-11, while `--lum-page`
  // rendered '#f5f7f9' and '#0a0e12', so neither theme actually matched.
  //
  // These two describe the *machine*. Once a viewer makes an explicit choice they stop
  // describing the page, so `lib/theme.ts` adds an unscoped third meta ahead of them — see
  // syncBrowserChrome there.
  themeColor: [
    { media: '(prefers-color-scheme: light)', color: PAGE_COLOUR.light },
    { media: '(prefers-color-scheme: dark)', color: PAGE_COLOUR.dark },
  ],
  // Room for the notch and the home indicator — a standalone PWA gets the whole screen,
  // including the parts of it a phone puts hardware in front of.
  viewportFit: 'cover',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  // Unlike the terminal, the console follows the viewer's theme by default — it is a phone app
  // used in daylight and in bed. M4-11 adds the override, and the script below is what makes an
  // override survive a reload without a flash.
  //
  // It is the first thing in the body and it blocks: the browser parses it and runs it before it
  // paints anything beneath. Applying the choice in an effect instead would mean a viewer whose
  // phone is light and whose choice is dark watches the app render white and then correct itself,
  // on every single load. suppressHydrationWarning because this script mutates the element React
  // is about to reconcile, which is the whole point of it running first.
  return (
    <html lang="en" suppressHydrationWarning>
      <body>
        <script dangerouslySetInnerHTML={{ __html: beforeFirstPaintScript() }} />
        {children}
      </body>
    </html>
  );
}
