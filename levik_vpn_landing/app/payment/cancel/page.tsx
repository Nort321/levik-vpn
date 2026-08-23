import type { Metadata } from "next";
import Link from "next/link";
import { Brand } from "@/components/brand";
import {
  CreditCardIcon,
  OrdersIcon,
  PlansIcon,
} from "@/components/icons";

export const metadata: Metadata = {
  title: "Оплата не завершена",
  robots: {
    index: false,
    follow: false,
    nocache: true,
  },
};

export default function PaymentCancelPage() {
  return (
    <main className="payment-page" id="main-content">
      <div className="payment-page__header">
        <Brand />
      </div>
      <section className="payment-result">
        <span className="payment-result__icon payment-result__icon--muted">
          <CreditCardIcon height={38} width={38} />
        </span>
        <span className="section-kicker">Оплата не завершена</span>
        <h1>Заказ остался в кабинете</h1>
        <p>
          Списания не подтверждены. Вы можете вернуться к заказу и продолжить
          оплату либо выбрать другой тариф.
        </p>
        <div className="button-row">
          <Link className="button button--primary" href="/dashboard/orders">
            <OrdersIcon />
            Открыть заказы
          </Link>
          <Link className="button button--quiet" href="/dashboard/plans">
            <PlansIcon />
            Посмотреть тарифы
          </Link>
        </div>
      </section>
    </main>
  );
}
