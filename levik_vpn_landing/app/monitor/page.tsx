import type { Metadata } from "next";

import { MonitorFooter } from "@/components/monitor/monitor-footer";
import { MonitorHeader } from "@/components/monitor/monitor-header";
import { MonitorOverviewView } from "@/components/monitor/monitor-overview";
import { monitorServices } from "@/lib/monitor/catalog";
import { buildOverview } from "@/lib/monitor/classify";
import { getMonitorOverview } from "@/lib/server/monitor";

export const metadata: Metadata = {
  title: { absolute: "Levik Monitor — что происходит с интернетом прямо сейчас" },
  description: "Проверьте, почему не работает Discord, YouTube, Telegram, Steam и другие сервисы. Диагностика DNS, HTTPS, API и CDN из нескольких регионов.",
  keywords: ["сервисы не работают сегодня", "проверить интернет", "Discord не работает", "YouTube не работает", "Telegram не работает", "мониторинг интернета России"],
  alternates: { canonical: "https://mon.leviknet.com/" },
  openGraph: {
    type: "website",
    locale: "ru_RU",
    siteName: "Levik Monitor",
    title: "Levik Monitor — что происходит с интернетом",
    description: "Отличаем общий сбой от проблемы провайдера, региона или маршрута.",
    url: "https://mon.leviknet.com/",
    images: [{
      url: "https://mon.leviknet.com/assets/levik-monitor-og.png",
      width: 1200,
      height: 630,
      alt: "Levik Monitor — что происходит с интернетом прямо сейчас",
    }],
  },
  twitter: {
    card: "summary_large_image",
    title: "Levik Monitor — интернет-пульс России",
    description: "Проверка доступности популярных сервисов из нескольких регионов.",
    images: ["https://mon.leviknet.com/assets/levik-monitor-og.png"],
  },
};

export default async function MonitorPage() {
  const overview = await getMonitorOverview().catch(() =>
    buildOverview(monitorServices, new Map()),
  );
  const structuredData = {
    "@context": "https://schema.org",
    "@type": "WebSite",
    name: "Levik Monitor",
    url: "https://mon.leviknet.com/",
    inLanguage: "ru-RU",
    description: metadata.description,
    publisher: { "@type": "Organization", name: "Levik", url: "https://leviknet.com/" },
  };

  return (
    <div className="site-page monitor-page">
      <script type="application/ld+json">{JSON.stringify(structuredData).replaceAll("<", "\\u003c")}</script>
      <MonitorHeader />
      <main id="main-content">
        <MonitorOverviewView overview={overview} />
      </main>
      <MonitorFooter />
    </div>
  );
}
