import Link from "next/link";
import {
  revokeDeviceAction,
  rotateSubscriptionKeyAction,
  setSubscriptionShieldAction,
} from "@/lib/web/actions";
import { getDashboardView } from "@/lib/web/view-models";
import { EmptyState } from "@/components/dashboard/empty-state";
import { StatusBadge } from "@/components/dashboard/status-badge";
import {
  CalendarIcon,
  ConnectIcon,
  DeviceIcon,
  GaugeIcon,
  PlansIcon,
  RemoveDeviceIcon,
  RotateKeyIcon,
  ShieldCheckIcon,
  SubscriptionIcon,
} from "@/components/icons";
import { SubmitButton } from "@/components/submit-button";

export default async function SubscriptionsPage() {
  const view = await getDashboardView();

  return (
    <>
      <header className="dashboard-page-header">
        <div>
          <span className="section-kicker">Доступ</span>
          <h1>Ваши подписки</h1>
          <p>Срок действия, трафик, устройства и безопасное управление ключом.</p>
        </div>
        <Link className="button button--primary" href="/dashboard/plans">
          <PlansIcon />
          Купить или продлить
        </Link>
      </header>

      {view.subscriptions.length === 0 ? (
        <EmptyState
          action={
            <Link className="button button--primary" href="/dashboard/plans">
              <PlansIcon />
              Выбрать тариф
            </Link>
          }
          description="После оплаты здесь появится ваша подписка и кнопка подключения."
          icon={<SubscriptionIcon height={30} width={30} />}
          title="Активных подписок нет"
        />
      ) : (
        <div className="subscription-detail-list">
          {view.subscriptions.map((subscription) => {
            const trafficValue =
              typeof subscription.trafficPercent === "number"
                ? Math.min(Math.max(subscription.trafficPercent, 0), 100)
                : undefined;

            return (
              <article
                className="subscription-detail"
                id={`subscription-${subscription.id}`}
                key={subscription.id}
              >
                <div className="subscription-detail__head">
                  <div>
                    <span className="card-kicker">
                      {subscription.kind === "multi"
                        ? "Мультиподписка"
                        : subscription.kind === "lte"
                          ? "LTE-подписка"
                          : "VPN-подписка"}
                    </span>
                    <h2>{subscription.title}</h2>
                    <p>{subscription.subtitle}</p>
                  </div>
                  <StatusBadge
                    label={subscription.statusLabel}
                    status={subscription.status}
                  />
                </div>

                <dl className="subscription-detail__stats">
                  <div>
                    <dt>
                      <CalendarIcon />
                      Действует
                    </dt>
                    <dd>{subscription.expiresLabel}</dd>
                    {subscription.remainingLabel ? (
                      <span>{subscription.remainingLabel}</span>
                    ) : null}
                  </div>
                  <div>
                    <dt>
                      <GaugeIcon />
                      Трафик
                    </dt>
                    <dd>{subscription.trafficLimitLabel}</dd>
                    {subscription.trafficUsedLabel ? (
                      <span>{subscription.trafficUsedLabel}</span>
                    ) : null}
                  </div>
                  <div>
                    <dt>
                      <DeviceIcon />
                      Устройства
                    </dt>
                    <dd>
                      {subscription.devices.length} из {subscription.deviceLimit}
                    </dd>
                    <span>занято сейчас</span>
                  </div>
                </dl>

                {subscription.components ? (
                  <div className="subscription-components subscription-components--detail">
                    {Object.entries(subscription.components).map(([id, component]) => (
                      <div key={id}>
                        <strong>{component.title}</strong>
                        <span>
                          Устройства: {component.devices.length} из {component.deviceLimit}
                        </span>
                        <span>
                          Трафик: {component.trafficLimitLabel}
                          {component.trafficUsedLabel
                            ? `, ${component.trafficUsedLabel}`
                            : ""}
                        </span>
                      </div>
                    ))}
                  </div>
                ) : null}

                {trafficValue !== undefined ? (
                  <progress
                    aria-label={`Использованный трафик ${subscription.title}`}
                    className="traffic-progress"
                    max={100}
                    value={trafficValue}
                  />
                ) : null}

                {subscription.shieldSupported ? (
                  <section
                    className={`shield-control${subscription.shieldEnabled ? " shield-control--enabled" : ""}`}
                  >
                    <span className="shield-control__icon">
                      <ShieldCheckIcon />
                    </span>
                    <div className="shield-control__copy">
                      <div>
                        <h3>Levik Shield</h3>
                        <span className="shield-control__status">
                          {subscription.shieldEnabled ? "Включён" : "Выключен"}
                        </span>
                      </div>
                      <p>
                        Блокирует рекламные и отслеживающие домены. Работает
                        только в Happ и на всех устройствах этой подписки.
                      </p>
                      <small>
                        После изменения обновите подписку в Happ и переподключитесь.
                      </small>
                    </div>
                    <form action={setSubscriptionShieldAction}>
                      <input name="csrf" type="hidden" value={view.csrfToken} />
                      <input
                        name="subscriptionId"
                        type="hidden"
                        value={subscription.id}
                      />
                      <input
                        name="enabled"
                        type="hidden"
                        value={subscription.shieldEnabled ? "false" : "true"}
                      />
                      <SubmitButton
                        className={
                          subscription.shieldEnabled
                            ? "button button--quiet"
                            : "button button--primary"
                        }
                        pendingText="Сохраняем…"
                      >
                        <ShieldCheckIcon />
                        {subscription.shieldEnabled
                          ? "Выключить Shield"
                          : "Включить Shield"}
                      </SubmitButton>
                    </form>
                  </section>
                ) : null}

                <div className="subscription-detail__toolbar">
                  {subscription.canConnect ? (
                    <Link className="button button--primary" href="/dashboard/connect">
                      <ConnectIcon />
                      Подключить устройство
                    </Link>
                  ) : null}
                  {subscription.canRenew ? (
                    <Link className="button button--quiet" href="/dashboard/plans">
                      <CalendarIcon />
                      Продлить
                    </Link>
                  ) : null}
                  {subscription.canRotateKey ? (
                    <form action={rotateSubscriptionKeyAction}>
                      <input name="csrf" type="hidden" value={view.csrfToken} />
                      <input
                        name="subscriptionId"
                        type="hidden"
                        value={subscription.id}
                      />
                      <SubmitButton
                        className="button button--danger-ghost"
                        pendingText="Меняем ключ…"
                      >
                        <RotateKeyIcon />
                        Сменить ключ
                      </SubmitButton>
                    </form>
                  ) : null}
                </div>

                <div className="device-section">
                  <div className="device-section__head">
                    <h3>Подключённые устройства</h3>
                    <span>
                      {subscription.devices.length} / {subscription.deviceLimit}
                    </span>
                  </div>
                  {subscription.devices.length > 0 ? (
                    <ul className="device-list">
                      {subscription.devices.map((device) => (
                        <li key={device.id}>
                          <span className="device-list__icon">
                            <DeviceIcon />
                          </span>
                          <div>
                            <strong>{device.name}</strong>
                            <span>
                              {[device.platform, device.lastSeenLabel]
                                .filter(Boolean)
                                .join(" · ")}
                            </span>
                          </div>
                          {device.isCurrent ? (
                            <span className="device-list__current">Это устройство</span>
                          ) : (
                            <form action={revokeDeviceAction}>
                              <input
                                name="csrf"
                                type="hidden"
                                value={view.csrfToken}
                              />
                              <input
                                name="subscriptionId"
                                type="hidden"
                                value={subscription.id}
                              />
                              <input name="deviceId" type="hidden" value={device.id} />
                              <SubmitButton
                                className="button button--danger-ghost button--compact"
                                pendingText="Отключаем…"
                              >
                                <RemoveDeviceIcon />
                                Отключить
                              </SubmitButton>
                            </form>
                          )}
                        </li>
                      ))}
                    </ul>
                  ) : (
                    <p className="device-section__empty">
                      Устройств пока нет. Откройте инструкцию подключения, чтобы
                      добавить первое.
                    </p>
                  )}
                </div>

                {subscription.canRotateKey ? (
                  <aside className="security-note">
                    <RotateKeyIcon />
                    <p>
                      <strong>Смена ключа отключит все текущие подключения.</strong>
                      Используйте её только если ссылка могла попасть к постороннему.
                    </p>
                  </aside>
                ) : null}
              </article>
            );
          })}
        </div>
      )}
    </>
  );
}
