import Link from "next/link";
import { ArrowUpRightIcon, CreditCardIcon } from "@/components/icons";
import { StatusBadge } from "@/components/dashboard/status-badge";
import type { OrderView } from "@/components/view-types";

type OrderListProps = {
  orders: OrderView[];
  compact?: boolean;
};

export function OrderList({ orders, compact = false }: OrderListProps) {
  return (
    <div className={compact ? "order-list order-list--compact" : "order-list"}>
      {orders.map((order) => (
        <article className="order-row" key={order.publicId}>
          <span className="order-row__icon">
            <CreditCardIcon />
          </span>
          <div className="order-row__main">
            <strong>{order.title}</strong>
            <span>
              {order.createdLabel} · {order.paymentMethodLabel}
            </span>
          </div>
          <strong className="order-row__amount">{order.amountLabel}</strong>
          <StatusBadge label={order.statusLabel} status={order.status} />
          {order.canContinuePayment && order.paymentPath?.startsWith("/payment/") ? (
            <Link className="text-link" href={order.paymentPath}>
              Продолжить
              <ArrowUpRightIcon />
            </Link>
          ) : null}
        </article>
      ))}
    </div>
  );
}
