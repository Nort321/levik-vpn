import Link from "next/link";
import {
  CalendarIcon,
  ConnectIcon,
  DeviceIcon,
  GaugeIcon,
  SubscriptionIcon,
} from "@/components/icons";
import { StatusBadge } from "@/components/dashboard/status-badge";
import type { SubscriptionView } from "@/components/view-types";

type SubscriptionSummaryProps = {
  subscription: SubscriptionView;
};

export function SubscriptionSummary({
  subscription,
}: SubscriptionSummaryProps) {
  const trafficValue =
    typeof subscription.trafficPercent === "number"
      ? Math.min(Math.max(subscription.trafficPercent, 0), 100)
      : undefined;

  return (
    <article className="subscription-summary">
      <div className="subscription-summary__head">
        <div>
          <span className="card-kicker">
            {subscription.kind === "multi"
              ? "Два режима в одном ключе"
              : subscription.kind === "lte"
                ? "Мобильная сеть"
                : "Основная подписка"}
          </span>
          <h3>{subscription.title}</h3>
          <p>{subscription.subtitle}</p>
        </div>
        <StatusBadge
          label={subscription.statusLabel}
          status={subscription.status}
        />
      </div>

      <dl className="subscription-summary__metrics">
        <div>
          <dt>
            <CalendarIcon />
            Действует
          </dt>
          <dd>{subscription.expiresLabel}</dd>
        </div>
        <div>
          <dt>
            <DeviceIcon />
            Устройства
          </dt>
          <dd>
            {subscription.devices.length} из {subscription.deviceLimit}
          </dd>
        </div>
        <div>
          <dt>
            <GaugeIcon />
            Трафик
          </dt>
          <dd>{subscription.trafficLimitLabel}</dd>
        </div>
      </dl>

      {subscription.components ? (
        <div className="subscription-components">
          {Object.entries(subscription.components).map(([id, component]) => (
            <div key={id}>
              <strong>{component.title}</strong>
              <span>
                {component.devices.length} из {component.deviceLimit} устройств
              </span>
              <span>{component.trafficLimitLabel} трафика</span>
            </div>
          ))}
        </div>
      ) : null}

      {trafficValue !== undefined ? (
        <div className="traffic-meter">
          <div>
            <span>{subscription.trafficUsedLabel ?? "Использовано"}</span>
            <span>{subscription.trafficLimitLabel}</span>
          </div>
          <progress aria-label="Использованный трафик" max={100} value={trafficValue} />
        </div>
      ) : null}

      <div className="subscription-summary__actions">
        {subscription.canConnect ? (
          <Link className="button button--primary" href="/dashboard/connect">
            <ConnectIcon />
            Подключить устройство
          </Link>
        ) : null}
        <Link
          className="button button--quiet"
          href={`/dashboard/subscriptions#subscription-${subscription.id}`}
        >
          <SubscriptionIcon />
          Управление
        </Link>
      </div>
    </article>
  );
}
