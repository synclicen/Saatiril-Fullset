import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Static export — for Electron production builds and Android APK.
  // Generates `out/` directory that can be served by any HTTP server.
  // In dev mode, set SAATIRIL_DEV=1 to disable this for hot-reload support.
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
    "127.0.0.1",
    "21.0.20.132",
  ],
};

export default nextConfig;
