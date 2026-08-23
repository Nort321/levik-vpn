"use client";

import Link from "next/link";
import {
  ArrowUpRightIcon,
  RefreshIcon,
  SupportIcon,
} from "@/components/icons";

export default function GlobalError({
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <html lang="ru">
      <head>
        <title>Сервис временно недоступен — Levik VPN</title>
      </head>
      <body>
        <main className="global-error-page">
          <section className="global-error-card" role="alert">
            <div className="global-error-card__mark" aria-hidden="true">
              LV
            </div>
            <span className="section-kicker">Levik VPN</span>
            <h1>Не удалось открыть страницу</h1>
            <p>
              Данные не потеряны. Попробуйте ещё раз или вернитесь на главную —
              если проблема сохраняется, используйте web-поддержку. Telegram
              остаётся дополнительным каналом.
            </p>
            <div className="button-row">
              <button
                className="button button--primary"
                onClick={reset}
                type="button"
              >
                <RefreshIcon />
                Повторить
              </button>
              <Link className="button button--quiet" href="/">
                На главную
                <ArrowUpRightIcon />
              </Link>
              <a
                className="button button--quiet"
                href="https://t.me/leviksupportbot"
              >
                <SupportIcon />
                Дополнительный Telegram-канал
              </a>
            </div>
          </section>
        </main>
      </body>
    </html>
  );
}
