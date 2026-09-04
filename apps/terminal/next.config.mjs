/**
 * The terminal's threat model is not the console's, and the headers differ accordingly.
 *
 * This is served on loopback and loaded by the Electron shell, which already refuses to
 * navigate away from itself (`electron/main.cjs`). What the headers add is a floor under
 * the renderer: it displays product names, customer names and cashier-typed text, all of
 * which arrive from the local database and none of which is escaped by anything other than
 * React. A CSP is what stops one of those becoming script execution inside a window that
 * sits next to the till's IPC bridge.
 *
 * No `Strict-Transport-Security`: this is http on 127.0.0.1 by design (see the desktop
 * profile's bind address), and asserting HSTS on a loopback origin would pin a browser to
 * a scheme that does not exist here.
 */

/** @type {import('next').NextConfig} */

/**
 * `script-src` carries `'unsafe-inline'` for two reasons, both real: Next's hydration
 * bootstrap, and `layout.tsx`'s theme script, which must run before first paint to avoid
 * flashing a light screen at a cashier in a dim shop. Both are inline by construction.
 *
 * `connect-src 'self'` is genuinely all that is needed — the rewrite below keeps `/api` on
 * this origin, so the renderer never makes a cross-origin request. A terminal that can only
 * talk to itself is the property worth locking in.
 */
const csp = [
  "default-src 'self'",
  "script-src 'self' 'unsafe-inline'",
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data:",
  "font-src 'self'",
  "connect-src 'self'",
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  "frame-ancestors 'none'",
].join('; ');

const nextConfig = {
  reactStrictMode: true,
  // The Electron bundle ships the standalone server output (M5-01).
  output: 'standalone',
  // Workspace packages are consumed as TypeScript source — no build step, no stale dist.
  transpilePackages: ['@lumora/domain', '@lumora/ui', '@lumora/api-client'],
  // The renderer calls /api/* on its own origin and Next forwards to the local backend.
  // Same-origin by construction: no CORS headers to configure, and no cross-origin
  // surface on a process that is the source of truth for the shop's money.
  async rewrites() {
    const backend = process.env.LUMORA_BACKEND_URL ?? 'http://127.0.0.1:8081';
    return [{ source: '/api/:path*', destination: `${backend}/api/:path*` }];
  },
  async headers() {
    return [
      {
        source: '/:path*',
        headers: [
          { key: 'Content-Security-Policy', value: csp },
          { key: 'X-Frame-Options', value: 'DENY' },
          { key: 'X-Content-Type-Options', value: 'nosniff' },
          { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
          { key: 'Permissions-Policy', value: 'camera=(), microphone=(), geolocation=()' },
        ],
      },
    ];
  },
};

export default nextConfig;
