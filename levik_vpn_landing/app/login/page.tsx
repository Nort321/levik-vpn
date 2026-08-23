import type { Metadata } from "next";
import { headers } from "next/headers";
import Link from "next/link";
import { redirect } from "next/navigation";

import { Brand } from "@/components/brand";
import { FreeProxyButton } from "@/components/free-proxy-button";
import {
  ArrowUpRightIcon,
  CheckIcon,
  LockIcon,
  RefreshIcon,
  ShieldCheckIcon,
  TelegramIcon,
} from "@/components/icons";
import { AccountAuth } from "@/components/login/account-auth";
import { AccountShieldIcon } from "@/components/account/account-icons";
import { LoginStatusPoller } from "@/components/login/login-status-poller";
import { SubmitButton } from "@/components/submit-button";
import { getOptionalSession } from "@/lib/server/browser-auth";
import { beginTelegramLoginAction } from "@/lib/web/actions";
import { getOptionalAccountOverview } from "@/lib/web/account-actions";
import { getLoginAttemptView } from "@/lib/web/view-models";

export const metadata: Metadata = {
  title: "Войти в Levik Account",
  description:
    "Вход в Levik Account с Google, passkey, Levik ID, recovery-кодом или дополнительной Telegram identity.",
  alternates: { canonical: "/login" },
  robots: { index: false, follow: false },
};

type LoginPageProps = {
  searchParams: Promise<{ next?: string; notice?: string }>;
};

function safeNext(value: string | undefined): string {
  if (!value || !value.startsWith("/") || value.startsWith("//")) {
    return "/dashboard/account-security";
  }
  const url = new URL(value, "https://leviknet.com");
  const accountRoute =
    url.pathname.startsWith("/dashboard/account-") ||
    url.pathname.startsWith("/dashboard/identities") ||
    url.pathname.startsWith("/dashboard/passkeys") ||
    url.pathname.startsWith("/dashboard/recovery") ||
    url.pathname.startsWith("/dashboard/sessions") ||
    url.pathname.startsWith("/dashboard/devices") ||
    url.pathname.startsWith("/dashboard/support");
  if (
    url.origin !== "https://leviknet.com" ||
    (!accountRoute &&
      url.pathname !== "/activate" &&
      url.pathname !== "/account/delete")
  ) {
    return "/dashboard/account-security";
  }
  return `${url.pathname}${url.search}`;
}

export default async function LoginPage({ searchParams }: LoginPageProps) {
  const params = await searchParams;
  const returnTo = safeNext(params.next);
  const [legacySession, account, attempt, requestHeaders] = await Promise.all([
    getOptionalSession(),
    getOptionalAccountOverview(),
    getLoginAttemptView(),
    headers(),
  ]);
  if (account) redirect(returnTo);
  if (legacySession) redirect("/dashboard");

  const cspNonce = requestHeaders.get("x-nonce") ?? undefined;

  return (
    <main className="auth-page auth-page--account" id="main-content">
      <div className="auth-page__header">
        <Brand />
        <Link className="text-link" href="/">
          На главную
          <ArrowUpRightIcon />
        </Link>
      </div>

      <div className="auth-shell auth-shell--account">
        <section className="auth-copy">
          <span className="section-kicker">Levik Account</span>
          <h1>Ваш доступ не зависит от почты или мессенджера</h1>
          <p>
            Используйте passkey, Google или собственный Levik ID. Telegram
            остаётся дополнительным способом для тех, кто уже им пользуется.
          </p>
          <ul className="security-list">
            <li>
              <ShieldCheckIcon />
              Основной идентификатор — внутренний account ID
            </li>
            <li>
              <LockIcon />
              Email не нужен для входа и восстановления
            </li>
            <li>
              <CheckIcon />
              Recovery-код используется один раз и затем становится недействительным
            </li>
          </ul>
          <aside className="auth-recovery-warning">
            Если потерять все связанные способы входа, passkeys и recovery-коды,
            автоматическое восстановление аккаунта невозможно.
          </aside>
        </section>

        <section aria-labelledby="login-card-title" className="auth-card auth-card--account">
          <div className="auth-card__icon">
            <ShieldCheckIcon height={30} width={30} />
          </div>
          <h2 id="login-card-title">Войти в аккаунт</h2>
          <p>Выберите доступный вам способ. Ни один из них не требует email.</p>

          {params.notice === "signed_out" ? (
            <div className="inline-message inline-message--success" role="status">
              <strong>Вы вышли из аккаунта</strong>
              <span>Сеанс на этом устройстве завершён.</span>
            </div>
          ) : null}

          <AccountAuth cspNonce={cspNonce} returnTo={returnTo} />

          <div className="auth-divider"><span>Новый аккаунт</span></div>
          <Link
            className="button button--quiet button--wide button--large"
            href={`/signup?next=${encodeURIComponent(returnTo)}`}
          >
            <AccountShieldIcon />
            Создать Levik Account
          </Link>

          <div className="auth-divider"><span>Дополнительный способ</span></div>

          <details className="auth-method auth-method--telegram" open={attempt.state === "pending"}>
            <summary>
              <TelegramIcon />
              <span>Войти через Telegram identity</span>
            </summary>

            {attempt.state === "idle" ? (
              <div className="auth-method__body">
                <p>
                  Для существующих пользователей. Telegram не становится
                  основным идентификатором Levik Account.
                </p>
                <form action={beginTelegramLoginAction}>
                  <SubmitButton
                    className="button button--quiet button--wide"
                    pendingText="Создаём запрос…"
                  >
                    <TelegramIcon />
                    Продолжить через Telegram
                  </SubmitButton>
                </form>
                <small>Подтверждение откроется только в @{attempt.botUsername}.</small>
              </div>
            ) : null}

            {attempt.state === "pending" ? (
              <div className="auth-method__body">
                <p>Сверьте код с сообщением бота и подтвердите вход в Telegram.</p>
                <div
                  aria-label={`Код подтверждения ${attempt.verificationCode}`}
                  className="verification-code"
                  role="status"
                >
                  {attempt.verificationCode}
                </div>
                <a
                  className="button button--primary button--wide"
                  href={attempt.telegramOpenPath}
                  rel="noopener noreferrer"
                  target="_blank"
                >
                  <TelegramIcon />
                  Открыть @{attempt.botUsername}
                </a>
                <LoginStatusPoller
                  expiresAt={attempt.expiresAt}
                  initialExpiresLabel={attempt.expiresLabel}
                  pollAfterMs={attempt.pollAfterMs}
                />
                <form action={beginTelegramLoginAction}>
                  <SubmitButton
                    className="button button--quiet button--wide"
                    pendingText="Обновляем…"
                  >
                    <RefreshIcon />
                    Получить новый код
                  </SubmitButton>
                </form>
              </div>
            ) : null}

            {attempt.state === "expired" || attempt.state === "error" ? (
              <div className="auth-method__body">
                <div className="inline-message inline-message--warning" role="alert">
                  <strong>Telegram-вход не завершён</strong>
                  <span>{attempt.message}</span>
                </div>
                <form action={beginTelegramLoginAction}>
                  <SubmitButton
                    className="button button--quiet button--wide"
                    pendingText="Создаём запрос…"
                  >
                    <RefreshIcon />
                    Попробовать снова
                  </SubmitButton>
                </form>
              </div>
            ) : null}
          </details>

          <div className="auth-card__proxy">
            <FreeProxyButton
              className="button button--ghost button--wide"
              label="Получить бесплатный Telegram proxy"
            />
          </div>

          <p className="auth-card__legal">
            Продолжая, вы принимаете <Link href="/legal/terms">условия</Link> и{" "}
            <Link href="/legal/privacy">политику конфиденциальности</Link>.
          </p>
        </section>
      </div>
    </main>
  );
}
