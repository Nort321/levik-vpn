import { randomUUID } from "node:crypto";
import { createOrderAction } from "@/lib/web/actions";
import {
  CheckIcon,
  CryptoIcon,
  SbpIcon,
  ShieldCheckIcon,
} from "@/components/icons";
import { SubmitButton } from "@/components/submit-button";
import type { PlanView, PlansView } from "@/components/view-types";

type PlanCardProps = {
  csrfToken: string;
  paymentMethods: PlansView["paymentMethods"];
  plan: PlanView;
};

export function PlanCard({
  csrfToken,
  paymentMethods,
  plan,
}: PlanCardProps) {
  const purchasable = plan.periods.length > 0 && paymentMethods.length > 0;
  const startingPeriod = plan.periods[0];

  return (
    <article className={plan.recommended ? "plan-card plan-card--recommended" : "plan-card"}>
      {plan.recommended ? <span className="plan-card__recommended">Популярный</span> : null}
      <div className="plan-card__head">
        <span className="card-kicker">{plan.eyebrow}</span>
        <h2>{plan.title}</h2>
        <p>{plan.description}</p>
      </div>

      <div className="plan-card__limits">
        <span>{plan.trafficLabel}</span>
        <span>{plan.deviceLimitLabel}</span>
      </div>

      {startingPeriod ? (
        <div className="plan-card__price">
          <strong>от {startingPeriod.priceLabel}</strong>
          <span>{startingPeriod.label}</span>
        </div>
      ) : null}

      <ul className="plan-features">
        {plan.features.map((feature) => (
          <li key={feature}>
            <CheckIcon />
            {feature}
          </li>
        ))}
      </ul>

      {purchasable ? (
        <form action={createOrderAction} className="plan-form">
          <input name="csrf" type="hidden" value={csrfToken} />
          <input name="kind" type="hidden" value="purchase" />
          <input name="tariffId" type="hidden" value={plan.tariffId} />
          <input name="idempotencyKey" type="hidden" value={randomUUID()} />

          {plan.periods.length === 1 && startingPeriod ? (
            <input
              name="periodMonths"
              type="hidden"
              value={startingPeriod.months}
            />
          ) : (
            <label className="plan-form__control">
              <span>Срок подписки</span>
              <select
                defaultValue={startingPeriod?.months}
                name="periodMonths"
                required
              >
                {plan.periods.map((period) => (
                  <option key={period.months} value={period.months}>
                    {period.label} · {period.priceLabel}
                    {period.effectiveMonthlyLabel
                      ? ` · ${period.effectiveMonthlyLabel}`
                      : ""}
                  </option>
                ))}
              </select>
            </label>
          )}

          <fieldset>
            <legend>Способ оплаты</legend>
            <div className="choice-grid">
              {paymentMethods.map((method, index) => {
                const PaymentIcon =
                  method.id === "sbp" ? SbpIcon : CryptoIcon;
                return (
                  <label
                    className="choice-card choice-card--payment"
                    key={method.id}
                  >
                    <input
                      defaultChecked={index === 0}
                      name="paymentMethod"
                      required
                      type="radio"
                      value={method.id}
                    />
                    <span>
                      <PaymentIcon />
                      <strong>{method.label}</strong>
                      <small>{method.description}</small>
                    </span>
                  </label>
                );
              })}
            </div>
          </fieldset>

          <SubmitButton
            className="button button--primary button--wide button--large"
            pendingText="Создаём заказ…"
          >
            <ShieldCheckIcon />
            Перейти к оплате
          </SubmitButton>
          <p className="plan-form__note">
            Итоговая сумма и состав заказа будут проверены на сервере до оплаты.
          </p>
        </form>
      ) : (
        <div className="inline-message inline-message--warning">
          <strong>Покупка временно недоступна</strong>
          <span>Обновите страницу или попробуйте немного позже.</span>
        </div>
      )}
    </article>
  );
}
