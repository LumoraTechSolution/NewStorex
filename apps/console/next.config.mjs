/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  transpilePackages: ['@lumora/domain', '@lumora/ui', '@lumora/api-client'],
  env: {
    NEXT_PUBLIC_API_BASE_URL: process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://127.0.0.1:8082',
  },
};

export default nextConfig;
