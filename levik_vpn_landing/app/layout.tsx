import type { Metadata, Viewport } from "next";
import type { ReactNode } from "react";
import "flag-icons/css/flag-icons.min.css";
import "@/app/globals.css";

export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  metadataBase: new URL("https://leviknet.com"),
  title: {
    default: "Levik VPN — обычный и мобильный VPN в одной подписке",
    template: "%s — Levik VPN",
  },
  description:
    "Мультиподписка Levik VPN: безлимитные обычные серверы и 50 ГБ мобильного трафика в одном ключе Happ. Управление в личном кабинете.",
  applicationName: "Levik VPN",
  alternates: {
    canonical: "/",
  },
  icons: {
    icon: "/assets/levik-shield.png",
    apple: "/assets/levik-shield.png",
  },
  openGraph: {
    type: "website",
    locale: "ru_RU",
    siteName: "Levik VPN",
    title: "Levik VPN — Мультиподписка в одном ключе",
    description:
      "Безлимитные обычные серверы и 50 ГБ мобильного трафика в одном ключе Happ.",
    images: [
      {
        url: "/assets/levik-og.png",
        width: 1200,
        height: 630,
        alt: "Levik VPN — свобода онлайн",
      },
    ],
  },
  twitter: {
    card: "summary_large_image",
    title: "Levik VPN — Мультиподписка",
    description:
      "Обычный и мобильный VPN в одном ключе Happ.",
    images: ["/assets/levik-og.png"],
  },
};

export const viewport: Viewport = {
  colorScheme: "dark",
  themeColor: "#030814",
  width: "device-width",
  initialScale: 1,
};

export default function RootLayout({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <html lang="ru">
      <body>
        <a className="skip-link" href="#main-content">
          Перейти к содержимому
        </a>
        <div aria-hidden="true" className="page-aurora" />
        {children}
      </body>
    </html>
  );
}
