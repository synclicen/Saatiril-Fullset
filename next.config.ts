import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Static export — required for Electron production builds.
  // When building with `next build`, this generates a static `out/` directory
  // that the Electron main process serves via its own HTTP server.
  output: "export",
  
  // Disable image optimization (not available in static export)
  images: {
    unoptimized: true,
  },
  
  // Trailing slashes for static file serving
  trailingSlash: true,
  
  typescript: {
    ignoreBuildErrors: true,
  },
  reactStrictMode: false,
  allowedDevOrigins: [
    ".space-z.ai",
    ".z.ai",
    "localhost",
  ],
};

export default nextConfig;
