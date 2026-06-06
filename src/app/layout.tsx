import type { Metadata, Viewport } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";
import { Toaster } from "@/components/ui/toaster";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  maximumScale: 1,
  userScalable: false,
  viewportFit: "cover",
  themeColor: "#1a0b2e",
};

export const metadata: Metadata = {
  title: "SAATIRIL - Pro System",
  description: "Sistem Auto Track Input, Raw into Live — Sistem manajemen fotografi acara real-time dengan dukungan multi-kamera dan distribusi LAN.",
  keywords: ["SAATIRIL", "photography", "event management", "real-time", "camera"],
  authors: [{ name: "Fajrianor" }],
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="id" suppressHydrationWarning>
      <body
        className={`${geistSans.variable} ${geistMono.variable} antialiased bg-[#1a0b2e] text-white h-dvh overflow-hidden`}
        style={{ paddingBottom: 'env(safe-area-inset-bottom, 0px)' }}
      >
        <div className="h-full flex flex-col">
          {children}
        </div>
        <Toaster />
      </body>
    </html>
  );
}
