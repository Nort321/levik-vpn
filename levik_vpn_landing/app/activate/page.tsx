import type { Metadata } from "next";
import { headers } from "next/headers";
import Link from "next/link";

import { ActionFeedback } from "@/components/account/action-feedback";
import { AccountDeviceIcon, AccountShieldIcon } from "@/components/account/account-icons";
import { Brand } from "@/components/brand";
import {
  ArrowUpRightIcon,
  CheckIcon,
  ClockIcon,
  LockIcon,
  ShieldCheckIcon,
  TelegramIcon,
} from "@/components/icons";
import { AccountAuth } from "@/components/login/account-auth";
import { SubmitButton } from "@/components/submit-button";
import {
  completeActivationAction,
  getActivationView,
  getOptionalAccountOverview,
} from "@/lib/web/account-actions";

export const metadata: Metadata = {
  title: "Подтвердить устройство",
  description: "Безопасная активация приложения Levik VPN через Levik Account.",
  alternates: { canonical: "/activate" },
  robots: { index: false, follow: false, nocache: true },
};

type ActivatePageProps = {
  searchParams: Promise<{
    code?: string;
    error?: string;
    notice?: string;
  }>;
};

const DATE_FORMATTER = new Intl.DateTimeFormat("ru-RU", {
  hour: "2-digit",
  minute: "2-digit",
  timeZone: "Europe/Moscow",
});

export default async function ActivatePage({ searchParams }: ActivatePageProps) {
  const params = await searchParams;
  const code = typeof params.code === "string" ? params.code : "";
  const [activation, account, requestHeaders] = await Promise.all([
    getActivationView(code),
    getOptionalAccountOverview(),
    headers(),
  ]);
  const completed = params.notice === "completed" || activation.state === "completed";
  const returnTo = `/activate?code=${encodeURIComponent(code)}`;

  return (
    <main className="auth-page activation-page" id="main-content">
      <div className="auth-page__header">
        <Brand />
        <Link className="text-link" href="/">
          На главную
          <ArrowUpRightIcon />
        </Link>
      </div>

      <div className="activation-shell">
        {completed ? (
          <section className="activation-result" role="status">
            <span className="activation-result__icon"><CheckIcon height={36} width={36} /></span>
            <span className="section-kicker">Готово</span>
            <h1>Устройство подтверждено</h1>
            <p>
              Вернитесь в приложение Levik VPN: оно продолжит активацию и
              покажет результат. VPN-профиль будет выдан только после
              подтверждения права доступа сервером.
            </p>
            <Link className="button button--quiet" href="/dashboard/devices">
              <AccountDeviceIcon />
              Управление устройствами
            </Link>
          </section>
        ) : null}

        {!completed && activation.state === "invalid" ? (
          <section className="activation-result activation-result--warning">
            <span className="activation-result__icon"><LockIcon height={34} width={34} /></span>
            <span className="section-kicker">Ссылка недействительна</span>
            <h1>Создайте новый запрос в приложении</h1>
            <p>
              Код отсутствует, повреждён или уже использован. Мы не пытаемся
              угадать его и не принимаем access token через URL.
            </p>
          </section>
        ) : null}

        {!completed && activation.state === "expired" ? (
          <section className="activation-result activation-result--warning">
            <span className="activation-result__icon"><ClockIcon height={34} width={34} /></span>
            <span className="section-kicker">Время вышло</span>
            <h1>Код активации истёк</h1>
            <p>
              Откройте приложение и запросите новую ссылку. Истёкший код нельзя
              продлить или использовать повторно.
            </p>
          </section>
        ) : null}

        {!completed && activation.state === "pending" ? (
          <div className="activation-grid">
            <section className="activation-device">
              <span className="activation-device__icon"><AccountDeviceIcon height={34} width={34} /></span>
              <span className="section-kicker">Запрос приложения</span>
              <h1>Подтвердить новое устройство?</h1>
              <dl>
                <div><dt>Устройство</dt><dd>{activation.device.name}</dd></div>
                <div><dt>Платформа</dt><dd>{activation.device.platform}</dd></div>
                <div>
                  <dt>Код действует до</dt>
                  <dd>{DATE_FORMATTER.format(new Date(activation.expiresAt))} МСК</dd>
                </div>
              </dl>
              <aside className="security-note">
                <ShieldCheckIcon />
                <p>
                  Подтверждайте только устройство, на котором вы сами начали
                  вход. Сайт не передаёт пароль, identity token или токен сеанса
                  обратно через URL.
                </p>
              </aside>
            </section>

            <section aria-labelledby="activation-action-title" className="auth-card activation-card">
              <div className="auth-card__icon"><LockIcon height={30} width={30} /></div>
              <h2 id="activation-action-title">
                {account ? "Подтвердите привязку" : "Сначала войдите"}
              </h2>
              <p>
                {account
                  ? `Устройство будет связано с Levik ID ${account.account.levikId}.`
                  : "Выберите способ Levik Account. После входа вы вернётесь к этому запросу."}
              </p>
              <ActionFeedback error={params.error} notice={params.notice} />

              {account ? (
                <form action={completeActivationAction} className="account-form">
                  <input name="csrf" type="hidden" value={account.csrfToken} />
                  <input name="code" type="hidden" value={activation.code} />
                  <SubmitButton
                    className="button button--primary button--wide button--large"
                    pendingText="Подтверждаем…"
                  >
                    <CheckIcon />
                    Подтвердить это устройство
                  </SubmitButton>
                </form>
              ) : (
                <>
                  <AccountAuth
                    cspNonce={requestHeaders.get("x-nonce") ?? undefined}
                    returnTo={returnTo}
                  />
                  <Link
                    className="button button--quiet button--wide activation-telegram-link"
                    href={`/signup?next=${encodeURIComponent(returnTo)}`}
                  >
                    <AccountShieldIcon />
                    Создать Levik Account
                  </Link>
                  <Link
                    className="button button--quiet button--wide activation-telegram-link"
                    href={`/login?next=${encodeURIComponent(returnTo)}`}
                  >
                    <TelegramIcon />
                    Telegram identity (дополнительно)
                  </Link>
                </>
              )}
            </section>
          </div>
        ) : null}
      </div>
    </main>
  );
}
