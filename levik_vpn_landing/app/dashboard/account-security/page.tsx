import Link from "next/link";

import { AccountNav } from "@/components/account/account-nav";
import {
  AccountDeviceIcon,
  AccountShieldIcon,
  BrowserSessionIcon,
  DeleteAccountIcon,
  IdentityIcon,
  PasskeyIcon,
  RecoveryIcon,
} from "@/components/account/account-icons";
import { formatAccountDate } from "@/components/account/date-time";
import { ArrowUpRightIcon, CheckIcon, LockIcon, ShieldCheckIcon } from "@/components/icons";
import { getAccountOverview } from "@/lib/web/account-actions";

export default async function AccountSecurityPage() {
  const view = await getAccountOverview();
  const hasPassword = view.identities.some((identity) => identity.provider === "password");
  const hasExternalIdentity = view.identities.some(
    (identity) => identity.provider === "google" || identity.provider === "telegram",
  );
  const completedChecks = [
    hasPassword || hasExternalIdentity,
    view.passkeys.length > 0,
    view.recoveryCodesRemaining > 0,
  ].filter(Boolean).length;
  const currentSession = view.sessions.find((session) => session.current);

  return (
    <>
      <header className="dashboard-page-header">
        <div>
          <span className="section-kicker">Levik Account</span>
          <h1>Безопасность аккаунта</h1>
          <p>
            Levik ID <strong>{view.account.levikId}</strong> · аккаунт создан{" "}
            {formatAccountDate(view.account.createdAt)}
          </p>
        </div>
        <span className={`account-status account-status--${view.account.status}`}>
          <ShieldCheckIcon />
          {view.account.status === "active"
            ? "Активен"
            : view.account.status === "deletion_pending"
              ? "Удаление ожидается"
              : "Доступ ограничен"}
        </span>
      </header>

      <AccountNav current="/dashboard/account-security" />

      <section className="account-security-hero">
        <span className="account-security-hero__icon"><AccountShieldIcon height={38} width={38} /></span>
        <div>
          <span className="card-kicker">{completedChecks} из 3 уровней настроено</span>
          <h2>
            {completedChecks === 3
              ? "Есть независимые способы восстановления"
              : "Добавьте резервный способ доступа"}
          </h2>
          <p>
            Надёжная конфигурация включает identity или парольную фразу,
            passkey и сохранённые offline recovery-коды.
          </p>
        </div>
        <strong>{completedChecks}/3</strong>
      </section>

      <div className="account-overview-grid">
        <Link className="account-overview-card" href="/dashboard/identities">
          <span><IdentityIcon /></span>
          <strong>Способы входа</strong>
          <p>{view.identities.length} привязано · Telegram не обязателен</p>
          <ArrowUpRightIcon />
        </Link>
        <Link className="account-overview-card" href="/dashboard/passkeys">
          <span><PasskeyIcon /></span>
          <strong>Passkeys</strong>
          <p>{view.passkeys.length > 0 ? `${view.passkeys.length} настроено` : "Не настроены"}</p>
          <ArrowUpRightIcon />
        </Link>
        <Link className="account-overview-card" href="/dashboard/recovery">
          <span><RecoveryIcon /></span>
          <strong>Recovery-коды</strong>
          <p>{view.recoveryCodesRemaining} неиспользованных</p>
          <ArrowUpRightIcon />
        </Link>
        <Link className="account-overview-card" href="/dashboard/sessions">
          <span><BrowserSessionIcon /></span>
          <strong>Сеансы</strong>
          <p>{view.sessions.length} активных · {currentSession?.deviceName ?? "текущее устройство"}</p>
          <ArrowUpRightIcon />
        </Link>
        <Link className="account-overview-card" href="/dashboard/devices">
          <span><AccountDeviceIcon /></span>
          <strong>Устройства приложения</strong>
          <p>{view.devices.length} привязано</p>
          <ArrowUpRightIcon />
        </Link>
        <Link className="account-overview-card account-overview-card--danger" href="/account/delete">
          <span><DeleteAccountIcon /></span>
          <strong>Удаление аккаунта</strong>
          <p>Отозвать identities, passkeys, сеансы и устройства</p>
          <ArrowUpRightIcon />
        </Link>
      </div>

      <aside className="account-critical-note">
        <LockIcon />
        <div>
          <strong>Восстановление не может обойти криптографическую защиту</strong>
          <p>
            Если вы потеряете все identities, парольную фразу, passkeys и
            recovery-коды, автоматическое восстановление невозможно. Поддержка
            не выдаёт доступ по имени, платежу или сообщению в Telegram.
          </p>
        </div>
      </aside>

      {completedChecks === 3 ? (
        <div className="account-feedback account-feedback--success" role="status">
          <CheckIcon />
          <span>Основные уровни восстановления настроены.</span>
        </div>
      ) : null}
    </>
  );
}
