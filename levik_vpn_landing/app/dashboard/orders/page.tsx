import Link from "next/link";
import { getOrdersView } from "@/lib/web/view-models";
import { EmptyState } from "@/components/dashboard/empty-state";
import { OrderList } from "@/components/dashboard/order-list";
import { OrdersIcon, PlansIcon } from "@/components/icons";

export default async function OrdersPage() {
  const view = await getOrdersView();

  return (
    <>
      <header className="dashboard-page-header">
        <div>
          <span className="section-kicker">Платежи</span>
          <h1>История заказов</h1>
          <p>
            Статус обновляется только после подтверждения платёжного провайдера.
          </p>
        </div>
        <Link className="button button--primary" href="/dashboard/plans">
          <PlansIcon />
          Новый заказ
        </Link>
      </header>

      <section className="dashboard-section dashboard-section--panel">
        {view.orders.length > 0 ? (
          <OrderList orders={view.orders} />
        ) : (
          <EmptyState
            action={
              <Link className="button button--primary" href="/dashboard/plans">
                <PlansIcon />
                Посмотреть тарифы
              </Link>
            }
            description="После создания заказа здесь появятся его сумма, способ оплаты и текущий статус."
            icon={<OrdersIcon height={30} width={30} />}
            title="История пока пустая"
          />
        )}
      </section>

      <aside className="security-note">
        <OrdersIcon />
        <p>
          <strong>Возвращение со страницы оплаты не означает успешный платёж.</strong>
          Кабинет активирует подписку только после отдельной серверной проверки.
        </p>
      </aside>
    </>
  );
}
