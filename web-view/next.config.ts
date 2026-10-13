import type { NextConfig } from "next";

const API_BASE_URL = process.env.API_BASE_URL ?? "http://localhost:9000";

const nextConfig: NextConfig = {
  cacheComponents: true,
  partialPrefetching: true,
  // Proxy backend calls through Next.js so the browser stays same-origin —
  // the Spring gateway has no CORS configuration and doesn't need one.
  async rewrites() {
    return [
      {
        source: "/backend/:path*",
        destination: `${API_BASE_URL}/:path*`,
      },
    ];
  },
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
};

export default nextConfig;
