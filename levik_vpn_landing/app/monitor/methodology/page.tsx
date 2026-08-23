import type { Metadata } from "next";
import Link from "next/link";

import { DiagnosticIcon, GlobeIcon, RouteIcon, ShieldCheckIcon } from "@/components/icons";
import { MonitorFooter } from "@/components/monitor/monitor-footer";
import { MonitorHeader } from "@/components/monitor/monitor-header";

export const metadata: Metadata = {
  title: { absolute: "Как Levik Monitor определяет сетевые проблемы" },
  description: "Методика проверки DNS, TCP, TLS, HTTP, API и CDN, правила определения деградации, недоступности и вероятного ограничения доступа.",
  alternates: { canonical: "https://mon.leviknet.com/methodology" },
  openGraph: {
    type: "article",
    locale: "ru_RU",
    siteName: "Levik Monitor",
    title: "Как Levik Monitor определяет сетевые проблемы",
    description: "Открытая методика диагностики доступности интернет-сервисов.",
    url: "https://mon.leviknet.com/methodology",
    images: [{ url: "https://mon.leviknet.com/assets/levik-monitor-og.png", width: 1200, height: 630 }],
  },
  twitter: {
    card: "summary_large_image",
    title: "Как Levik Monitor определяет сетевые проблемы",
    description: "DNS, TCP, TLS, HTTP, API и CDN — открытая методика диагностики.",
    images: ["https://mon.leviknet.com/assets/levik-monitor-og.png"],
  },
};

export default function MonitorMethodologyPage() {
  return (
    <div className="site-page monitor-page methodology-page">
      <MonitorHeader />
      <main className="container methodology" id="main-content">
        <header>
          <p className="eyebrow"><span aria-hidden="true" className="live-dot" />Открытая методика</p>
          <h1>Как Monitor локализует проблему</h1>
          <p>Мы разделяем факт измерения и вывод. Один неудачный запрос не превращается в заявление о массовом сбое.</p>
        </header>
        <section className="methodology-grid">
          <article><span className="feature-card__icon"><DiagnosticIcon /></span><h2>Уровни проверки</h2><ol><li>DNS-резолвинг</li><li>TCP-соединение с портом 443</li><li>TLS handshake и сертификат</li><li>HTTP-ответ главной страницы</li><li>Ответы API и CDN</li></ol></article>
          <article><span className="feature-card__icon"><GlobeIcon /></span><h2>Сравнение регионов</h2><p>Каждая точка делает одинаковые проверки раз в минуту. Сбой считается глобальным только когда он подтверждается из независимых сетей.</p></article>
          <article><span className="feature-card__icon"><RouteIcon /></span><h2>Проверка из браузера</h2><p>Браузер проверяет доступность сайта, API и CDN со своего подключения. Из-за ограничений браузера этот слой не выдаётся за отдельное измерение DNS, TCP или TLS.</p></article>
          <article><span className="feature-card__icon"><GlobeIcon /></span><h2>Пользовательские сигналы</h2><p>Результаты группируются по сети и региону без сохранения IP. Проценты публикуются только при выборке от 10 свежих проверок и не смешиваются с серверными точками.</p></article>
          <article><span className="feature-card__icon"><RouteIcon /></span><h2>Ограничение доступа</h2><p>Фиолетовый статус возможен только при достаточном числе противоположных региональных сигналов. Это вероятностный технический вывод, а не утверждение о причине или регуляторе.</p></article>
          <article><span className="feature-card__icon"><ShieldCheckIcon /></span><h2>Confidence</h2><p>Уверенность зависит от числа свежих независимых точек и согласованности результатов. При нехватке данных Monitor прямо сообщает об этом.</p></article>
        </section>
        <aside className="methodology-note"><strong>Чего Monitor не делает</strong><p>Не сохраняет IP посетителей в результатах браузерной проверки, не считает ручное нажатие доказательством сбоя, не подменяет отсутствие телеметрии выдуманной статистикой и не объявляет блокировку без технических оснований.</p></aside>
        <Link className="button button--primary button--large" href="https://mon.leviknet.com/">Вернуться к интернет-пульсу</Link>
      </main>
      <MonitorFooter />
    </div>
  );
}
