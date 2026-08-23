import type { OrderStatus, SubscriptionStatus } from "@/components/view-types";

type StatusBadgeProps = {
  status: OrderStatus | SubscriptionStatus;
  label: string;
};

const statusTone: Record<OrderStatus | SubscriptionStatus, string> = {
  active: "success",
  limited: "warning",
  expired: "muted",
  disabled: "danger",
  pending: "warning",
  paid: "info",
  delivered: "success",
  cancelled: "muted",
  failed: "danger",
};

export function StatusBadge({ status, label }: StatusBadgeProps) {
  return (
    <span className={`status-badge status-badge--${statusTone[status]}`}>
      <span aria-hidden="true" />
      {label}
    </span>
  );
}
