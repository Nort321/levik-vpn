import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

import type { BridgeSnapshot } from "@/lib/server/bridge/cabinet";
import { mobileAccountSnapshot } from "@/lib/server/mobile-account";

const SNAPSHOT = {
  ok: true,
  user: {
    userKey: "usr_0123456789abcdefghijklmnop",
    userLabel: "Test user",
  },
  trial: {
    eligible: false,
    status: "unavailable",
    expiresAt: null,
  },
  referrals: {
    invited: 1,
    rewarded: 1,
    discountPercent: 5,
    rewardDays: 3,
    referralLink: "https://t.me/levikvpnbot?start=ref_123",
  },
  subscriptions: [
    {
      uuid: "018d1557-d946-7c03-8c42-f83a43d91c8e",
      tariffId: "standard",
      title: "Standard",
      status: "active",
      expireAt: "2026-08-01T00:00:00.000Z",
      subscriptionUrl: "https://subscriptions.example/secret-token",
      traffic: {
        usedBytes: 100,
        limitBytes: 1_000,
      },
      devices: {
        used: 1,
        limit: 3,
        items: [{ id: "device-1", label: "Pixel" }],
      },
      shield: {
        supported: true,
        enabled: true,
      },
      actions: {
        renew: true,
        rotateKey: true,
        revokeDevice: true,
        slotAddon: true,
        trafficAddon: true,
      },
    },
  ],
  orders: [
    {
      id: 1,
      kind: "access_purchase",
      status: "pending",
      tariffId: "standard",
      months: 1,
      amountRub: 199,
      paymentMethodId: "card",
      createdAt: "2026-07-29T00:00:00.000Z",
      paymentUrl: "https://app.platega.io/secret-payment",
    },
  ],
  freeProxy: {
    available: true,
    active: false,
  },
} satisfies BridgeSnapshot;

describe("mobile account projection", () => {
  it("never exposes subscription or payment URLs", () => {
    const account = mobileAccountSnapshot(SNAPSHOT);
    const serialized = JSON.stringify(account);

    expect(serialized).not.toContain("subscriptionUrl");
    expect(serialized).not.toContain("paymentUrl");
    expect(serialized).not.toContain("secret-token");
    expect(account.subscriptions[0]?.uuid).toBe(
      "018d1557-d946-7c03-8c42-f83a43d91c8e",
    );
  });

  it("preserves unavailable referrals for pure accounts", () => {
    const account = mobileAccountSnapshot({
      ...SNAPSHOT,
      referrals: null,
    });

    expect(account.referrals).toBeNull();
  });
});
