/**
 * The console is the only part of this product on the public internet, and the only one
 * holding a bearer token in a browser. `src/lib/api.ts` explains why that token lives in
 * localStorage rather than an httpOnly cookie — the API is on another origin, so a cookie
 * would have to be `SameSite=None`, which iOS makes unreliable. The consequence of that
 * trade is that any XSS here reads the session, so the headers below are the second line
 * of defence the cookie would otherwise have provided.
 */

/** The cloud API's origin, which the console must be allowed to call and to reach for CSP. */
const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://127.0.0.1:8082';

/**
 * Content-Security-Policy, and the one compromise in it.
 *
 * `script-src` carries `'unsafe-inline'` because Next injects an inline bootstrap script for
 * hydration on every page, and a nonce cannot be threaded through a statically exported PWA.
 * Stating it here rather than quietly omitting the directive: a CSP that breaks the app on
 * deploy gets removed by whoever is on call, and then there is no CSP at all. Everything
 * else is as tight as it goes.
 *
 * `connect-src` names the API explicitly. The console talks to exactly one backend, and a
 * stolen token is only useful to an attacker who can also reach somewhere to send it.
 */
/**
 * `next dev` compiles every module into an `eval()`'d string so it can hot-reload it, so a CSP
 * without `'unsafe-eval'` stops the console dead in development: nothing hydrates, `page.tsx`
 * returns null until an effect has run, and the browser shows a blank screen with one console
 * error. It looks exactly like a dev server that failed to start.
 *
 * `next build` sets NODE_ENV to production, so the deployed header is unchanged — the shipped
 * console still refuses eval, which is the only place that promise matters.
 */
const development = process.env.NODE_ENV !== 'production';

const csp = [
  "default-src 'self'",
  `script-src 'self' 'unsafe-inline'${development ? " 'unsafe-eval'" : ''}`,
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data:",
  "font-src 'self'",
  `connect-src 'self' ${API_BASE_URL}`,
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  // Belt to X-Frame-Options' braces: this is the directive browsers actually honour now,
  // and clickjacking a console that shows a shop's takings is worth closing twice.
  "frame-ancestors 'none'",
  'upgrade-insecure-requests',
].join('; ');

/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  transpilePackages: ['@lumora/domain', '@lumora/ui', '@lumora/api-client'],
  env: {
    NEXT_PUBLIC_API_BASE_URL: API_BASE_URL,
  },
  async headers() {
    return [
      {
        source: '/:path*',
        headers: [
          { key: 'Content-Security-Policy', value: csp },
          // Superseded by frame-ancestors above, kept for browsers that predate it.
          { key: 'X-Frame-Options', value: 'DENY' },
          { key: 'X-Content-Type-Options', value: 'nosniff' },
          // Sends the origin cross-site and the full path same-site, so the API base URL
          // and any path a viewer is on stop leaking to third parties in a Referer header.
          { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
          // Nothing here uses a camera, a microphone or a location, and saying so stops a
          // future dependency asking for one on this origin.
          { key: 'Permissions-Policy', value: 'camera=(), microphone=(), geolocation=()' },
          // Two years, subdomains included. Safe to assert: the console is served over
          // HTTPS by Vercel and has no plaintext deployment to strand. Not preloaded —
          // that is a submission to a browser list and belongs to a deliberate decision,
          // not to a config change.
          {
            key: 'Strict-Transport-Security',
            value: 'max-age=63072000; includeSubDomains',
          },
        ],
      },
    ];
  },
};

export default nextConfig;
