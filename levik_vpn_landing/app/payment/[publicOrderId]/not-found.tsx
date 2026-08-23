import Link from "next/link";
import { Brand } from "@/components/brand";
import { AlertIcon, OrdersIcon, PlansIcon } from "@/components/icons";

export default function PaymentNotFound() {
  return (
    <main className="payment-page" id="main-content">
      <div className="payment-page__header">
        <Brand />
      </div>
      <section className="payment-result">
        <span className="payment-result__icon payment-result__icon--muted">
          <AlertIcon height={38} width={38} />
        </span>
        <span className="section-kicker">Заказ не найден</span>
        <h1>Эта страница оплаты недоступна</h1>
        <p>
          Заказ мог истечь или принадлежать другому сеансу. Проверьте свою историю
          — чужие заказы кабинет не показывает.
        </p>
        <div className="button-row">
          <Link className="button button--primary" href="/dashboard/orders">
            <OrdersIcon />
            Открыть заказы
          </Link>
          <Link className="button button--quiet" href="/dashboard/plans">
            <PlansIcon />
            Выбрать тариф
          </Link>
        </div>
      </section>
    </main>
  );
}
