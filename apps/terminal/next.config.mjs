/** @type {import('next').NextConfig} */
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
};

export default nextConfig;
