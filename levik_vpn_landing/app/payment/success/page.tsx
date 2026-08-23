import type { Metadata } from "next";
import Link from "next/link";
import { getOrdersView } from "@/lib/web/view-models";
import { Brand } from "@/components/brand";
import {
  CheckIcon,
  ClockIcon,
  OrdersIcon,
  SubscriptionIcon,
} from "@/components/icons";

export const metadata: Metadata = {
  title: "Проверяем оплату",
  robots: {
    index: false,
    follow: false,
    nocache: true,
  },
};

export default async function PaymentSuccessPage() {
  const view = await getOrdersView();
  const latestOrder = view.orders.at(0);
  const delivered =
    latestOrder?.status === "delivered" || latestOrder?.status === "paid";

  return (
    <main className="payment-page" id="main-content">
      <div className="payment-page__header">
        <Brand />
      </div>
      <section className="payment-result">
        <span
          className={
            delivered
              ? "payment-result__icon payment-result__icon--success"
              : "payment-result__icon"
          }
        >
          {delivered ? (
            <CheckIcon height={38} width={38} />
          ) : (
            <ClockIcon height={38} width={38} />
          )}
        </span>
        <span className="section-kicker">
          {delivered ? "Платёж подтверждён" : "Проверяем платёж"}
        </span>
        <h1>
          {delivered
            ? "Подписка скоро будет готова"
            : "Можно закрыть страницу оплаты"}
        </h1>
        <p>
          {delivered
            ? "Мы получили подтверждение. Доступ появится в кабинете после завершения безопасной выдачи."
            : "Возвращение от провайдера ещё не подтверждает оплату. Статус обновится после отдельной серверной проверки."}
        </p>
        <div className="button-row">
          <Link className="button button--primary" href="/dashboard/subscriptions">
            <SubscriptionIcon />
            Проверить подписки
          </Link>
          <Link className="button button--quiet" href="/dashboard/orders">
            <OrdersIcon />
            Открыть заказы
          </Link>
        </div>
      </section>
    </main>
  );
}
