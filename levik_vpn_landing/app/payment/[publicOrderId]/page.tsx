import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { getOrdersView } from "@/lib/web/view-models";
import { Brand } from "@/components/brand";
import { StatusBadge } from "@/components/dashboard/status-badge";
import {
  ArrowUpRightIcon,
  CreditCardIcon,
  LockIcon,
  OrdersIcon,
  ShieldCheckIcon,
} from "@/components/icons";

export const metadata: Metadata = {
  title: "Оплата заказа",
  robots: {
    index: false,
    follow: false,
    nocache: true,
  },
};

type PaymentPageProps = {
  params: Promise<{ publicOrderId: string }>;
};

export default async function PaymentPage({ params }: PaymentPageProps) {
  const { publicOrderId } = await params;

  if (!/^[A-Za-z0-9_-]{8,80}$/.test(publicOrderId)) {
    notFound();
  }

  const view = await getOrdersView();
  const order = view.orders.find((item) => item.publicId === publicOrderId);

  if (!order) {
    notFound();
  }

  return (
    <main className="payment-page" id="main-content">
      <div className="payment-page__header">
        <Brand />
        <Link className="text-link" href="/dashboard/orders">
          Все заказы
          <OrdersIcon />
        </Link>
      </div>
      <section className="payment-card">
        <span className="payment-card__icon">
          <CreditCardIcon height={32} width={32} />
        </span>
        <div className="payment-card__head">
          <span className="section-kicker">Заказ {order.publicId}</span>
          <h1>{order.title}</h1>
          <StatusBadge label={order.statusLabel} status={order.status} />
        </div>
        <dl className="payment-summary">
          <div>
            <dt>Сумма</dt>
            <dd>{order.amountLabel}</dd>
          </div>
          <div>
            <dt>Способ оплаты</dt>
            <dd>{order.paymentMethodLabel}</dd>
          </div>
          <div>
            <dt>Создан</dt>
            <dd>{order.createdLabel}</dd>
          </div>
        </dl>

        {order.canContinuePayment ? (
          <form action="/api/payment/open" method="post">
            <input name="csrf" type="hidden" value={view.csrfToken} />
            <input name="publicOrderId" type="hidden" value={order.publicId} />
            <button className="button button--primary button--wide button--large" type="submit">
              <LockIcon />
              Перейти к оплате
              <ArrowUpRightIcon />
            </button>
          </form>
        ) : (
          <Link className="button button--primary button--wide" href="/dashboard/orders">
            <OrdersIcon />
            Вернуться к заказам
          </Link>
        )}

        <aside className="payment-security">
          <ShieldCheckIcon />
          <p>
            Оплата откроется на сайте платёжного провайдера. Мы не получаем и не
            храним данные вашей карты.
          </p>
        </aside>
      </section>
    </main>
  );
}
