import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { MonitorFooter } from "@/components/monitor/monitor-footer";
import { MonitorHeader } from "@/components/monitor/monitor-header";
import { ServiceDetail } from "@/components/monitor/service-detail";
import { getMonitorService } from "@/lib/monitor/catalog";
import { classifyService } from "@/lib/monitor/classify";
import {
  getMonitorHistory,
  getMonitorIncidents,
  getMonitorServiceSnapshot,
  getMonitorUserSignals,
} from "@/lib/server/monitor";

type ServicePageProps = { params: Promise<{ service: string }> };

export async function generateMetadata({ params }: ServicePageProps): Promise<Metadata> {
  const { service: slug } = await params;
  const service = getMonitorService(slug);
  if (!service) return {};
  const title = `${service.name} не работает сегодня? Статус и причины сбоя`;
  const description = `Актуальный статус ${service.name}: серверная диагностика DNS, HTTPS, API и CDN, анонимные пользовательские проверки и история проблем за 24 часа.`;
  const url = `https://mon.leviknet.com/${service.slug}`;
  return {
    title: { absolute: `${title} — Levik Monitor` },
    description,
    keywords: [`${service.name} не работает`, `${service.name} сегодня`, `статус ${service.name}`, `сбой ${service.name}`, `${service.name} Россия`],
    alternates: { canonical: url },
    openGraph: { type: "website", locale: "ru_RU", siteName: "Levik Monitor", title, description, url, images: [] },
    twitter: { card: "summary", title, description, images: [] },
  };
}

export default async function MonitorServicePage({ params }: ServicePageProps) {
  const { service: slug } = await params;
  const service = getMonitorService(slug);
  if (!service) notFound();
  const [snapshot, history, incidents, userSignals] = await Promise.all([
    getMonitorServiceSnapshot(slug).catch(() => classifyService(service, [])),
    getMonitorHistory(slug).catch(() => []),
    getMonitorIncidents(slug).catch(() => []),
    getMonitorUserSignals(slug).catch(() => ({
      windowMinutes: 15,
      totalChecks: 0,
      failedChecks: 0,
      successRate: null,
      confirmedReports: 0,
      sufficientData: false,
      updatedAt: null,
      countries: [],
      networks: [],
    })),
  ]);
  if (!snapshot) notFound();
  const title = `${service.name} не работает сегодня?`;
  const structuredData = {
    "@context": "https://schema.org",
    "@graph": [
      {
        "@type": "WebPage",
        name: title,
        url: `https://mon.leviknet.com/${service.slug}`,
        description: `Статус и диагностика доступности ${service.name} из нескольких регионов.`,
        dateModified: snapshot.updatedAt ?? undefined,
        isPartOf: { "@type": "WebSite", name: "Levik Monitor", url: "https://mon.leviknet.com/" },
      },
      {
        "@type": "FAQPage",
        mainEntity: [
          { "@type": "Question", name: `Почему ${service.name} не работает?`, acceptedAnswer: { "@type": "Answer", text: snapshot.diagnosis } },
          { "@type": "Question", name: `Как проверяется ${service.name}?`, acceptedAnswer: { "@type": "Answer", text: "Levik Monitor независимо проверяет DNS, TCP 443, TLS, главную страницу, API и CDN с нескольких сетевых точек." } },
        ],
      },
    ],
  };

  return (
    <div className="site-page monitor-page service-detail-page">
      <script type="application/ld+json">{JSON.stringify(structuredData).replaceAll("<", "\\u003c")}</script>
      <MonitorHeader />
      <main id="main-content"><ServiceDetail history={history} incidents={incidents} snapshot={snapshot} userSignals={userSignals} /></main>
      <MonitorFooter />
    </div>
  );
}
