import { ActionFeedback } from "@/components/account/action-feedback";
import { AccountNav } from "@/components/account/account-nav";
import { BrowserSessionIcon } from "@/components/account/account-icons";
import { formatAccountDate } from "@/components/account/date-time";
import { ClockIcon, RemoveDeviceIcon } from "@/components/icons";
import { SubmitButton } from "@/components/submit-button";
import {
  getAccountOverview,
  revokeAccountSessionAction,
} from "@/lib/web/account-actions";

type SessionsPageProps = {
  searchParams: Promise<{ error?: string; notice?: string }>;
};

export default async function SessionsPage({ searchParams }: SessionsPageProps) {
  const [view, params] = await Promise.all([getAccountOverview(), searchParams]);
  return (
    <>
      <header className="dashboard-page-header">
        <div>
          <span className="section-kicker">Браузеры</span>
          <h1>Активные сеансы</h1>
          <p>Завершите незнакомые сеансы. Это не удаляет аккаунт или VPN-профиль.</p>
        </div>
      </header>
      <AccountNav current="/dashboard/sessions" />
      <ActionFeedback error={params.error} notice={params.notice} />

      <section className="dashboard-section dashboard-section--panel">
        <div className="dashboard-section__head">
          <div><span className="card-kicker">Levik Account</span><h2>Открытые сеансы</h2></div>
          <span className="session-count">{view.sessions.length}</span>
        </div>
        {view.sessions.length > 0 ? (
          <ul className="account-item-list">
            {view.sessions.map((session) => (
              <li key={session.id}>
                <span className="account-item-list__icon"><BrowserSessionIcon /></span>
                <div className="account-item-list__main">
                  <strong>{session.deviceName}</strong>
                  <span>{session.current ? "Текущий сеанс" : `Создан ${formatAccountDate(session.createdAt)}`}</span>
                  <small><ClockIcon /> Активность: {formatAccountDate(session.lastSeenAt)}</small>
                </div>
                {!session.current ? (
                  <form action={revokeAccountSessionAction}>
                    <input name="csrf" type="hidden" value={view.csrfToken} />
                    <input name="sessionId" type="hidden" value={session.id} />
                    <SubmitButton className="button button--danger-ghost button--compact" pendingText="Завершаем…">
                      <RemoveDeviceIcon />
                      Завершить
                    </SubmitButton>
                  </form>
                ) : (
                  <span className="account-item-list__current">Сейчас</span>
                )}
              </li>
            ))}
          </ul>
        ) : (
          <div className="account-empty-state">
            <BrowserSessionIcon height={30} width={30} />
            <h2>Активных сеансов нет</h2>
            <p>Обновите страницу. Возможно, текущий сеанс уже завершён.</p>
          </div>
        )}
      </section>
    </>
  );
}
