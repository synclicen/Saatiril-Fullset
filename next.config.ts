import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Static export — for Electron production builds only.
  // In CI (GitHub Actions), this is enabled to generate `out/` directory
  // that the Electron main process serves via its own HTTP server.
  // In dev mode, keep this commented out for hot-reload support.
  // output: "export",
  
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
