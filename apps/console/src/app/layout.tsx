import type { Metadata, Viewport } from 'next';

import './globals.css';

export const metadata: Metadata = {
  title: 'Lumora Console',
  description: 'Owner console — read-only view of your shop from anywhere',
};

export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  // Unlike the terminal, the console follows the viewer's theme — it is a phone app
  // used in daylight and in bed.
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
