import {
  logoutAction,
  revokeOtherSessionsAction,
  revokeSessionAction,
} from "@/lib/web/actions";
import { getSessionsView } from "@/lib/web/view-models";
import {
  ClockIcon,
  LockIcon,
  LogoutIcon,
  RemoveDeviceIcon,
  SessionIcon,
  ShieldCheckIcon,
} from "@/components/icons";
import { SubmitButton } from "@/components/submit-button";

export default async function SettingsPage() {
  const view = await getSessionsView();
  const otherSessions = view.sessions.filter((session) => !session.current);

  return (
    <>
      <header className="dashboard-page-header">
        <div>
          <span className="section-kicker">Безопасность</span>
          <h1>Сеансы и доступ</h1>
          <p>Проверьте, где открыт кабинет, и завершите незнакомые сеансы.</p>
        </div>
        {otherSessions.length > 0 ? (
          <form action={revokeOtherSessionsAction}>
            <input name="csrf" type="hidden" value={view.csrfToken} />
            <SubmitButton
              className="button button--danger-ghost"
              pendingText="Завершаем сеансы…"
            >
              <RemoveDeviceIcon />
              Завершить остальные
            </SubmitButton>
          </form>
        ) : null}
      </header>

      <section className="security-overview">
        <span className="security-overview__icon">
          <ShieldCheckIcon height={32} width={32} />
        </span>
        <div>
          <span className="card-kicker">Защищённый сеанс</span>
          <h2>Доступ подтверждён</h2>
          <p>
            Каждый браузер получает отдельный защищённый сеанс. Для новых входов
            используйте доступный способ Levik Account.
          </p>
        </div>
        <LockIcon height={34} width={34} />
      </section>

      <section className="dashboard-section dashboard-section--panel">
        <div className="dashboard-section__head">
          <div>
            <span className="card-kicker">Активность</span>
            <h2>Открытые сеансы</h2>
          </div>
          <span className="session-count">{view.sessions.length}</span>
        </div>

        {view.sessions.length > 0 ? (
          <ul className="session-list">
            {view.sessions.map((session) => (
              <li key={session.id}>
                <span className="session-list__icon">
                  <SessionIcon />
                </span>
                <div className="session-list__main">
                  <div>
                    <strong>{session.deviceLabel}</strong>
                    {session.current ? (
                      <span className="session-list__current">Текущий сеанс</span>
                    ) : null}
                  </div>
                  <span>
                    {[session.locationLabel, session.lastActiveLabel]
                      .filter(Boolean)
                      .join(" · ")}
                  </span>
                  <small>
                    <ClockIcon />
                    Вход: {session.createdLabel}
                  </small>
                </div>
                {!session.current ? (
                  <form action={revokeSessionAction}>
                    <input name="csrf" type="hidden" value={view.csrfToken} />
                    <input name="sessionId" type="hidden" value={session.id} />
                    <SubmitButton
                      className="button button--danger-ghost button--compact"
                      pendingText="Завершаем…"
                    >
                      <RemoveDeviceIcon />
                      Завершить
                    </SubmitButton>
                  </form>
                ) : null}
              </li>
            ))}
          </ul>
        ) : (
          <p className="device-section__empty">
            Активные сеансы не найдены. Обновите страницу или войдите заново.
          </p>
        )}
      </section>

      <aside className="security-note">
        <LockIcon />
        <p>
          <strong>Увидели незнакомое устройство?</strong>
          Завершите сеанс и смените ключ подписки. Данные сеанса и ключи VPN
          никогда не отправляйте в поддержку сообщением.
        </p>
      </aside>

      <section className="logout-panel">
        <div>
          <h2>Завершить текущий сеанс</h2>
          <p>На этом устройстве потребуется снова войти в Levik Account.</p>
        </div>
        <form action={logoutAction}>
          <input name="csrf" type="hidden" value={view.csrfToken} />
          <SubmitButton
            className="button button--danger-ghost"
            pendingText="Выходим…"
          >
            <LogoutIcon />
            Выйти из кабинета
          </SubmitButton>
        </form>
      </section>
    </>
  );
}
