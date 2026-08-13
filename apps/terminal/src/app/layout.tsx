import type { Metadata, Viewport } from 'next';

import './globals.css';

export const metadata: Metadata = {
  title: 'Lumora Terminal',
  description: 'Offline-first point of sale',
};

export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  maximumScale: 1,
  userScalable: false,
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  // data-surface pins the dark appliance palette. The terminal does not follow the OS
  // theme — the cashier's screen must look identical on every shift.
  return (
    <html lang="en" data-surface="terminal">
      <body>{children}</body>
    </html>
  );
}
