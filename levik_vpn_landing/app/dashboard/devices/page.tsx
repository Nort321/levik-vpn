import { ActionFeedback } from "@/components/account/action-feedback";
import { AccountNav } from "@/components/account/account-nav";
import { AccountDeviceIcon } from "@/components/account/account-icons";
import { formatAccountDate } from "@/components/account/date-time";
import { ClockIcon, RemoveDeviceIcon, ShieldCheckIcon } from "@/components/icons";
import { SubmitButton } from "@/components/submit-button";
import {
  getAccountOverview,
  revokeAccountDeviceAction,
} from "@/lib/web/account-actions";

type DevicesPageProps = {
  searchParams: Promise<{ error?: string; notice?: string }>;
};

export default async function DevicesPage({ searchParams }: DevicesPageProps) {
  const [view, params] = await Promise.all([getAccountOverview(), searchParams]);
  return (
    <>
      <header className="dashboard-page-header">
        <div>
          <span className="section-kicker">Приложение</span>
          <h1>Привязанные устройства</h1>
          <p>
            Каждое устройство получает отдельный device-bound session. Отзыв
            прекращает доступ этого устройства к аккаунту.
          </p>
        </div>
      </header>
      <AccountNav current="/dashboard/devices" />
      <ActionFeedback error={params.error} notice={params.notice} />

      <section className="dashboard-section dashboard-section--panel">
        <div className="dashboard-section__head">
          <div><span className="card-kicker">Android и другие клиенты</span><h2>Устройства аккаунта</h2></div>
          <span className="session-count">{view.devices.length}</span>
        </div>
        {view.devices.length > 0 ? (
          <ul className="account-item-list">
            {view.devices.map((device) => (
              <li key={device.id}>
                <span className="account-item-list__icon"><AccountDeviceIcon /></span>
                <div className="account-item-list__main">
                  <strong>{device.name}</strong>
                  <span>{device.platform}{device.current ? " · это устройство" : ""}</span>
                  <small><ClockIcon /> Последняя активность: {formatAccountDate(device.lastSeenAt)}</small>
                </div>
                {!device.current ? (
                  <form action={revokeAccountDeviceAction}>
                    <input name="csrf" type="hidden" value={view.csrfToken} />
                    <input name="deviceId" type="hidden" value={device.id} />
                    <SubmitButton className="button button--danger-ghost button--compact" pendingText="Отзываем…">
                      <RemoveDeviceIcon />
                      Отозвать
                    </SubmitButton>
                  </form>
                ) : (
                  <span className="account-item-list__current"><ShieldCheckIcon /> Текущее</span>
                )}
              </li>
            ))}
          </ul>
        ) : (
          <div className="account-empty-state">
            <AccountDeviceIcon height={30} width={30} />
            <h2>Устройства ещё не привязаны</h2>
            <p>Откройте Levik VPN и начните вход — приложение покажет ссылку /activate.</p>
          </div>
        )}
      </section>
    </>
  );
}
