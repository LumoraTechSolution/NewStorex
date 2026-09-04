import type { Metadata, Viewport } from 'next';
import { Archivo, Fraunces, IBM_Plex_Mono } from 'next/font/google';

import { beforeFirstPaintScript, PAGE_COLOUR } from '@/lib/theme';

import './globals.css';

/*
 * Three faces, self-hosted (M6-14).
 *
 * `next/font/google` downloads these at build time and serves them from this origin. That is not a
 * performance nicety here — `next.config.mjs` sets `font-src 'self'`, so a stylesheet link to
 * Google would be blocked by our own CSP, and loosening the CSP to admit a third party onto the
 * one page in this product that holds a bearer token is not a trade worth making for a typeface.
 * The consequence is that a build needs the network the first time; the fonts are then in
 * `.next/cache`.
 *
 * Archivo for the interface, Fraunces for money, IBM Plex Mono for the things that are codes rather
 * than words — invoice numbers and SKUs, which are read character by character.
 */
const sans = Archivo({ subsets: ['latin'], display: 'swap', variable: '--font-sans' });

const display = Fraunces({ subsets: ['latin'], display: 'swap', variable: '--font-display' });

const mono = IBM_Plex_Mono({
  subsets: ['latin'],
  weight: ['400', '500'],
  display: 'swap',
  variable: '--font-mono',
});

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
    <html
      lang="en"
      className={`${sans.variable} ${display.variable} ${mono.variable}`}
      suppressHydrationWarning
    >
      <body>
        <script dangerouslySetInnerHTML={{ __html: beforeFirstPaintScript() }} />
        {children}
      </body>
    </html>
  );
}
