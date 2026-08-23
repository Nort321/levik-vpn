import "server-only";

import type { BridgeSnapshot } from "@/lib/server/bridge/cabinet";

export function mobileAccountSnapshot(snapshot: BridgeSnapshot) {
  return {
    user: snapshot.user,
    trial: snapshot.trial,
    referrals: snapshot.referrals,
    subscriptions: snapshot.subscriptions.map((subscription) => ({
      uuid: subscription.uuid,
      tariffId: subscription.tariffId,
      title: subscription.title,
      status: subscription.status,
      expireAt: subscription.expireAt,
      traffic: subscription.traffic,
      devices: subscription.devices,
      actions: subscription.actions,
    })),
    orders: snapshot.orders.map((order) => ({
      id: order.id,
      kind: order.kind,
      status: order.status,
      tariffId: order.tariffId,
      months: order.months,
      amountRub: order.amountRub,
      paymentMethodId: order.paymentMethodId,
      createdAt: order.createdAt,
    })),
    freeProxy: snapshot.freeProxy,
  };
}
