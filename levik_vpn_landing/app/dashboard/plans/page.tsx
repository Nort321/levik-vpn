import { randomUUID } from "node:crypto";
import { createOrderAction } from "@/lib/web/actions";
import { getPlansView } from "@/lib/web/view-models";
import { EmptyState } from "@/components/dashboard/empty-state";
import { PlanCard } from "@/components/dashboard/plan-card";
import {
  CalendarIcon,
  CreditCardIcon,
  DeviceIcon,
  GaugeIcon,
  PlansIcon,
  RefreshIcon,
} from "@/components/icons";
import { SubmitButton } from "@/components/submit-button";

export default async function PlansPage() {
  const view = await getPlansView();

  return (
    <>
      <header className="dashboard-page-header">
        <div>
          <span className="section-kicker">Тарифы</span>
          <h1>Подключите подходящий VPN</h1>
          <p>
            Цена и доступные параметры приходят с сервера — их нельзя подменить
            в браузере.
          </p>
        </div>
      </header>

      {view.plans.length > 0 ? (
        <div className="plan-grid">
          {view.plans.map((plan) => (
            <PlanCard
              csrfToken={view.csrfToken}
              key={plan.tariffId}
              paymentMethods={view.paymentMethods}
              plan={plan}
            />
          ))}
        </div>
      ) : (
        <EmptyState
          description="Тарифы временно недоступны. Попробуйте обновить страницу немного позже."
          icon={<PlansIcon height={30} width={30} />}
          title="Не удалось получить тарифы"
        />
      )}

      {view.addons.length > 0 ? (
        <section className="dashboard-section">
          <div className="dashboard-section__head">
            <div>
              <span className="card-kicker">Дополнения</span>
              <h2>Расширить действующую подписку</h2>
            </div>
          </div>
          <div className="addon-grid">
            {view.addons.map((addon) => {
              const AddonIcon =
                addon.id === "slot_addon" ? DeviceIcon : GaugeIcon;
              const singleSubscription =
                addon.eligibleSubscriptions.length === 1
                  ? addon.eligibleSubscriptions[0]
                  : undefined;
              const available =
                addon.eligibleSubscriptions.length > 0 &&
                view.paymentMethods.length > 0;

              return (
                <article className="addon-card" key={addon.id}>
                  <span className="addon-card__icon">
                    <AddonIcon />
                  </span>
                  <div className="addon-card__head">
                    <div>
                      <span className="card-kicker">Разовая покупка</span>
                      <h3>{addon.title}</h3>
                    </div>
                    <strong>{addon.amountLabel}</strong>
                  </div>
                  <p>{addon.description}</p>

                  {available ? (
                    <form
                      action={createOrderAction}
                      className={
                        singleSubscription
                          ? "addon-form addon-form--single-target"
                          : "addon-form"
                      }
                    >
                      <input name="csrf" type="hidden" value={view.csrfToken} />
                      <input name="kind" type="hidden" value={addon.id} />
                      <input
                        name="idempotencyKey"
                        type="hidden"
                        value={randomUUID()}
                      />
                      {singleSubscription ? (
                        <input
                          name="targetSubscriptionId"
                          type="hidden"
                          value={singleSubscription.id}
                        />
                      ) : (
                        <label>
                          <span>Подписка</span>
                          <select name="targetSubscriptionId" required>
                            {addon.eligibleSubscriptions.map((subscription) => (
                              <option key={subscription.id} value={subscription.id}>
                                {subscription.kind === "multi"
                                  ? "Мультиподписка"
                                  : subscription.kind === "lte"
                                    ? "Мобильный VPN"
                                    : "Обычный VPN"}
                                {" — "}
                                {subscription.label}
                              </option>
                            ))}
                          </select>
                        </label>
                      )}
                      <label>
                        <span>Оплата</span>
                        <select name="paymentMethod" required>
                          {view.paymentMethods.map((method) => (
                            <option key={method.id} value={method.id}>
                              {method.label}
                            </option>
                          ))}
                        </select>
                      </label>
                      <SubmitButton pendingText="Создаём заказ…">
                        <AddonIcon />
                        Добавить
                      </SubmitButton>
                    </form>
                  ) : (
                    <div className="inline-message">
                      <strong>Сейчас недоступно</strong>
                      <span>
                        Нет подходящей активной подписки или способа оплаты.
                      </span>
                    </div>
                  )}
                </article>
              );
            })}
          </div>
        </section>
      ) : null}

      {view.renewableSubscriptions.length > 0 ? (
        <section className="renew-panel">
          <div className="renew-panel__copy">
            <span className="card-kicker">Уже с нами</span>
            <h2>Продлить действующую подписку</h2>
            <p>
              Выберите подписку и срок. Новое время добавится к текущему сроку
              после подтверждённой оплаты.
            </p>
          </div>
          <div className="renew-form-list">
            {view.renewableSubscriptions.map((subscription) => (
              <form
                action={createOrderAction}
                className="renew-form"
                key={subscription.id}
              >
                <input name="csrf" type="hidden" value={view.csrfToken} />
                <input name="kind" type="hidden" value="renewal" />
                <input
                  name="targetSubscriptionId"
                  type="hidden"
                  value={subscription.id}
                />
                <input
                  name="tariffId"
                  type="hidden"
                  value={subscription.tariffId}
                />
                <input name="idempotencyKey" type="hidden" value={randomUUID()} />
                <strong className="renew-form__title">{subscription.label}</strong>
                <label>
                  <span>Срок</span>
                  <select defaultValue="1" name="periodMonths" required>
                    <option value="1">1 месяц</option>
                    <option value="3">3 месяца</option>
                    <option value="6">6 месяцев</option>
                    <option value="12">12 месяцев</option>
                  </select>
                </label>
                <label>
                  <span>Оплата</span>
                  <select name="paymentMethod" required>
                    {view.paymentMethods.map((method) => (
                      <option key={method.id} value={method.id}>
                        {method.label}
                      </option>
                    ))}
                  </select>
                </label>
                <SubmitButton pendingText="Создаём продление…">
                  <RefreshIcon />
                  Продлить
                </SubmitButton>
              </form>
            ))}
          </div>
          <div className="renew-panel__trust">
            <CalendarIcon />
            Срок добавляется только после проверки платежа
            <CreditCardIcon />
            Платёж подтверждает провайдер
          </div>
        </section>
      ) : null}
    </>
  );
}
