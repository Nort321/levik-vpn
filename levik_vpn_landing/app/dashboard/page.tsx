import Link from "next/link";
import {
  activateTrialAction,
  claimFreeProxyAction,
} from "@/lib/web/actions";
import { getDashboardView } from "@/lib/web/view-models";
import { EmptyState } from "@/components/dashboard/empty-state";
import { OrderList } from "@/components/dashboard/order-list";
import { SubscriptionSummary } from "@/components/dashboard/subscription-summary";
import {
  CalendarIcon,
  BoltIcon,
  ConnectIcon,
  DeviceIcon,
  OrdersIcon,
  PlansIcon,
  ReferralIcon,
  SubscriptionIcon,
  TelegramIcon,
} from "@/components/icons";
import { SubmitButton } from "@/components/submit-button";

export default async function DashboardOverviewPage() {
  const view = await getDashboardView();

  return (
    <>
      <header className="dashboard-page-header">
        <div>
          <span className="section-kicker">Личный кабинет</span>
          <h1>Здравствуйте, {view.viewer.displayName}</h1>
          <p>Подписки, устройства и платежи — в одном защищённом месте.</p>
        </div>
        <Link className="button button--primary" href="/dashboard/plans">
          <PlansIcon />
          Выбрать тариф
        </Link>
      </header>

      {view.notices.length > 0 ? (
        <div className="notice-stack">
          {view.notices.map((notice) => (
            <aside
              className={`dashboard-notice dashboard-notice--${notice.tone}`}
              key={`${notice.title}-${notice.message}`}
            >
              <strong>{notice.title}</strong>
              <span>{notice.message}</span>
              {notice.action?.kind === "activate_trial" ? (
                <form action={activateTrialAction}>
                  <input name="csrf" type="hidden" value={view.csrfToken} />
                  <SubmitButton
                    className="button button--quiet button--compact"
                    pendingText="Активируем…"
                  >
                    <BoltIcon />
                    {notice.action.label}
                  </SubmitButton>
                </form>
              ) : null}
            </aside>
          ))}
        </div>
      ) : null}

      <dl className="dashboard-stats">
        <div>
          <dt>
            <SubscriptionIcon />
            Активные подписки
          </dt>
          <dd>{view.summary.activeSubscriptions}</dd>
        </div>
        <div>
          <dt>
            <CalendarIcon />
            Ближайшее окончание
          </dt>
          <dd>{view.summary.nearestExpiryLabel}</dd>
        </div>
        <div>
          <dt>
            <DeviceIcon />
            Подключённые устройства
          </dt>
          <dd className="dashboard-stats__devices">
            {view.summary.deviceUsage.length > 0
              ? view.summary.deviceUsage.map((usage) => (
                  <span key={usage.kind}>
                    <small>{usage.label}</small>
                    <strong>
                      {usage.connected}
                      <b> / {usage.limit}</b>
                    </strong>
                  </span>
                ))
              : "—"}
          </dd>
        </div>
      </dl>

      <section className="dashboard-section">
        <div className="dashboard-section__head">
          <div>
            <span className="card-kicker">Сейчас</span>
            <h2>Ваши подписки</h2>
          </div>
          {view.subscriptions.length > 0 ? (
            <Link className="text-link" href="/dashboard/subscriptions">
              Все подписки
            </Link>
          ) : null}
        </div>
        {view.subscriptions.length > 0 ? (
          <div className="subscription-grid">
            {view.subscriptions.map((subscription) => (
              <SubscriptionSummary
                key={subscription.id}
                subscription={subscription}
              />
            ))}
          </div>
        ) : (
          <EmptyState
            action={
              <Link className="button button--primary" href="/dashboard/plans">
                <PlansIcon />
                Выбрать первую подписку
              </Link>
            }
            description="Выберите обычный VPN или мобильный LTE — после оплаты подключение появится здесь."
            icon={<SubscriptionIcon height={28} width={28} />}
            title="Подписок пока нет"
          />
        )}
      </section>

      <div className="dashboard-columns">
        <section className="dashboard-section dashboard-section--panel">
          <div className="dashboard-section__head">
            <div>
              <span className="card-kicker">Telegram</span>
              <h2>Бесплатный proxy</h2>
            </div>
            <TelegramIcon height={26} width={26} />
          </div>
          <p className="dashboard-section__copy">
            {view.freeProxy.description}
          </p>
          <div className="proxy-status-line">
            <span aria-hidden="true" />
            {view.freeProxy.stateLabel}
          </div>
          {view.freeProxy.state === "available" ? (
            <form action={claimFreeProxyAction}>
              <input name="csrf" type="hidden" value={view.csrfToken} />
              <SubmitButton pendingText="Выдаём proxy…">
                <TelegramIcon />
                Получить proxy
              </SubmitButton>
            </form>
          ) : null}
          {view.freeProxy.state === "active" &&
          view.freeProxy.openPath === "/api/proxy/open" ? (
            <a
              className="button button--primary"
              href="/api/proxy/open"
              rel="noopener noreferrer"
              target="_blank"
            >
              <ConnectIcon />
              Открыть в Telegram
            </a>
          ) : null}
        </section>

        {view.referral ? (
          <section className="dashboard-section dashboard-section--panel">
            <div className="dashboard-section__head">
              <div>
                <span className="card-kicker">Вместе выгоднее</span>
                <h2>Пригласите друга</h2>
              </div>
              <ReferralIcon height={26} width={26} />
            </div>
            <p className="dashboard-section__copy">
              {view.referral.inviteeBenefitDescription}{" "}
              {view.referral.rewardDescription}
            </p>
            <dl className="referral-mini-stats">
              <div>
                <dt>Приглашено</dt>
                <dd>{view.referral.invitedCount}</dd>
              </div>
              <div>
                <dt>Получено дней</dt>
                <dd>{view.referral.rewardedDays}</dd>
              </div>
            </dl>
            <a
              className="button button--quiet"
              href={view.referral.sharePath}
              rel="noopener noreferrer"
              target="_blank"
            >
              <ReferralIcon />
              Поделиться в Telegram
            </a>
          </section>
        ) : null}
      </div>

      <section className="dashboard-section">
        <div className="dashboard-section__head">
          <div>
            <span className="card-kicker">История</span>
            <h2>Последние заказы</h2>
          </div>
          {view.recentOrders.length > 0 ? (
            <Link className="text-link" href="/dashboard/orders">
              Вся история
            </Link>
          ) : null}
        </div>
        {view.recentOrders.length > 0 ? (
          <OrderList compact orders={view.recentOrders.slice(0, 3)} />
        ) : (
          <EmptyState
            action={
              <Link className="button button--quiet" href="/dashboard/plans">
                <PlansIcon />
                Посмотреть тарифы
              </Link>
            }
            description="Здесь появятся оплаты, продления и дополнительные услуги."
            icon={<OrdersIcon height={28} width={28} />}
            title="Заказов пока нет"
          />
        )}
      </section>
    </>
  );
}
