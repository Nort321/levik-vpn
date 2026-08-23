import type { Metadata } from "next";
import Link from "next/link";

import { IpCheckDashboard } from "@/components/check/ip-check-dashboard";
import { Brand } from "@/components/brand";
import { ArrowUpRightIcon, ConnectIcon, ShieldCheckIcon } from "@/components/icons";
import { SiteFooter } from "@/components/site-footer";

export const metadata: Metadata = {
  metadataBase: new URL("https://check.leviknet.com"),
  title: "Узнать мой IP — проверка IP, VPN и WebRTC",
  description:
    "Узнайте свой IP-адрес, страну, город, провайдера, ASN и WHOIS/RDAP диапазона. Проверка IPv4 / IPv6, VPN, WebRTC leak и доступности сервисов.",
  keywords: [
    "узнать мой ip",
    "мой ip",
    "проверить ip",
    "проверка vpn",
    "webrtc leak test",
    "проверка ipv6",
    "ip адрес",
    "whois ip",
    "проверить провайдера по ip",
    "rdap lookup",
  ],
  alternates: { canonical: "https://check.leviknet.com/" },
  robots: { index: true, follow: true },
  openGraph: {
    type: "website",
    locale: "ru_RU",
    siteName: "Levik IP Check",
    url: "https://check.leviknet.com/",
    title: "Узнать мой IP и проверить защиту",
    description:
      "Публичный IP, провайдер, ASN, WHOIS/RDAP, геолокация, IPv6, WebRTC и доступность сервисов в одном тесте.",
    images: [
      {
        url: "https://leviknet.com/assets/levik-og.png",
        width: 1200,
        height: 630,
        alt: "Levik IP Check",
      },
    ],
  },
  twitter: {
    card: "summary_large_image",
    title: "Узнать мой IP — Levik IP Check",
    description: "Проверка IP, VPN, IPv6, WebRTC и доступности сервисов.",
    images: ["https://leviknet.com/assets/levik-og.png"],
  },
};

const structuredData = {
  "@context": "https://schema.org",
  "@graph": [
    {
      "@type": "WebApplication",
      name: "Levik IP Check",
      url: "https://check.leviknet.com/",
      applicationCategory: "SecurityApplication",
      operatingSystem: "Любая",
      inLanguage: "ru-RU",
      description:
        "Онлайн-проверка публичного IP, провайдера, ASN, WHOIS/RDAP, геолокации, IPv6, WebRTC и маршрута Levik VPN.",
      offers: { "@type": "Offer", price: "0", priceCurrency: "RUB" },
    },
    {
      "@type": "FAQPage",
      mainEntity: [
        {
          "@type": "Question",
          name: "Как узнать свой IP-адрес?",
          acceptedAnswer: {
            "@type": "Answer",
            text: "Откройте Levik IP Check: текущий публичный IP появится автоматически без установки приложений и доступа к геолокации.",
          },
        },
        {
          "@type": "Question",
          name: "Как понять, что Levik VPN работает?",
          acceptedAnswer: {
            "@type": "Answer",
            text: "Статус «Вы защищены Levik» появляется после совпадения текущего IP с живым списком выходных узлов Levik.",
          },
        },
        {
          "@type": "Question",
          name: "Что показывает WHOIS IP-адреса?",
          acceptedAnswer: {
            "@type": "Answer",
            text: "WHOIS/RDAP показывает официальные регистрационные сведения о сети: выделенный IP-диапазон, реестр, владельца блока и даты обновления. Это не персональные данные пользователя.",
          },
        },
      ],
    },
  ],
};

export default function IpCheckPage() {
  return (
    <div className="site-page check-page">
      <script type="application/ld+json">{JSON.stringify(structuredData)}</script>
      <header className="site-header check-header">
        <div className="container site-header__inner">
          <div className="check-brand">
            <Brand href="https://check.leviknet.com/" />
            <span>IP CHECK</span>
          </div>
          <nav aria-label="Навигация Levik IP Check" className="site-nav">
            <Link href="#services-title">Доступность сервисов</Link>
            <Link href="#faq-title">Вопросы</Link>
            <Link href="https://leviknet.com/status">Статус серверов</Link>
          </nav>
          <div className="site-header__actions">
            <Link className="button button--ghost check-header__main" href="https://leviknet.com/">
              Сайт Levik
              <ArrowUpRightIcon />
            </Link>
            <Link className="button button--primary" href="https://leviknet.com/dashboard">
              <ShieldCheckIcon />
              <span className="site-header__cabinet-label">Подключить Levik</span>
              <span className="site-header__cabinet-short">Защита</span>
            </Link>
          </div>
        </div>
      </header>
      <main id="main-content">
        <IpCheckDashboard />
        <section className="check-cta">
          <div className="container check-cta__inner">
            <div>
              <span className="section-kicker">Levik VPN</span>
              <h2>Ваш реальный IP всё ещё виден?</h2>
              <p>Подключите Levik, вернитесь сюда и убедитесь, что маршрут изменился.</p>
            </div>
            <Link className="button button--primary button--large" href="https://leviknet.com/">
              <ConnectIcon />
              Защитить соединение
            </Link>
          </div>
        </section>
      </main>
      <SiteFooter />
    </div>
  );
}
