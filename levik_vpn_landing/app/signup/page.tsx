import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";

import { AccountShieldIcon, RecoveryIcon } from "@/components/account/account-icons";
import { Brand } from "@/components/brand";
import { ArrowUpRightIcon, CheckIcon, LockIcon } from "@/components/icons";
import { AccountEnrollment } from "@/components/login/account-enrollment";
import { getOptionalSession } from "@/lib/server/browser-auth";
import { getOptionalAccountOverview } from "@/lib/web/account-actions";

export const metadata: Metadata = {
  title: "Создать Levik Account",
  description: "Создание Levik Account без email и обязательного Telegram.",
  alternates: { canonical: "/signup" },
  robots: { index: false, follow: false, nocache: true },
};

type SignupPageProps = {
  searchParams: Promise<{ next?: string }>;
};

function safeNext(value: string | undefined): string {
  if (!value || !value.startsWith("/") || value.startsWith("//")) {
    return "/dashboard/account-security";
  }
  const url = new URL(value, "https://leviknet.com");
  const allowed =
    url.origin === "https://leviknet.com" &&
    (url.pathname.startsWith("/dashboard/account-") ||
      url.pathname.startsWith("/dashboard/identities") ||
      url.pathname.startsWith("/dashboard/passkeys") ||
      url.pathname.startsWith("/dashboard/recovery") ||
      url.pathname.startsWith("/dashboard/sessions") ||
      url.pathname.startsWith("/dashboard/devices") ||
      url.pathname.startsWith("/dashboard/support") ||
      url.pathname === "/activate" ||
      url.pathname === "/account/delete");
  return allowed
    ? `${url.pathname}${url.search}`
    : "/dashboard/account-security";
}

export default async function SignupPage({ searchParams }: SignupPageProps) {
  const [params, legacySession, account] = await Promise.all([
    searchParams,
    getOptionalSession(),
    getOptionalAccountOverview(),
  ]);
  const returnTo = safeNext(params.next);
  if (account) redirect(returnTo);
  if (legacySession) redirect("/dashboard");

  return (
    <main className="auth-page auth-page--account" id="main-content">
      <div className="auth-page__header">
        <Brand />
        <Link className="text-link" href={`/login?next=${encodeURIComponent(returnTo)}`}>
          Уже есть аккаунт
          <ArrowUpRightIcon />
        </Link>
      </div>
      <div className="auth-shell auth-shell--account signup-shell">
        <section className="auth-copy">
          <span className="section-kicker">Новый Levik Account</span>
          <h1>Аккаунт без email и обязательного мессенджера</h1>
          <p>
            Сервер создаст независимый Levik ID. Google, Telegram и passkey можно
            добавить позже как отдельные способы входа.
          </p>
          <ul className="security-list">
            <li><AccountShieldIcon /> Основной идентификатор не зависит от провайдера</li>
            <li><LockIcon /> Парольная фраза хранится только как memory-hard hash</li>
            <li><RecoveryIcon /> Одноразовые recovery-коды показываются один раз</li>
          </ul>
          <aside className="auth-recovery-warning">
            Если потерять Levik ID и все способы входа, автоматическое
            восстановление невозможно. Поддержка не обходит эту защиту.
          </aside>
        </section>
        <section aria-labelledby="signup-card-title" className="auth-card auth-card--account">
          <div className="auth-card__icon"><CheckIcon height={30} width={30} /></div>
          <h2 id="signup-card-title">Создать аккаунт</h2>
          <p>Введите имя для кабинета и длинную парольную фразу.</p>
          <AccountEnrollment returnTo={returnTo} />
        </section>
      </div>
    </main>
  );
}
