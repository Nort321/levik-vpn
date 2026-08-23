import "server-only";

import { z } from "zod";

import { bridgeUserSchema } from "@/lib/server/bridge/auth";
import { bridgeCall } from "@/lib/server/bridge/core";
import { getEnvironment } from "@/lib/server/env";

const identifierSchema = z.string().regex(/^[A-Za-z0-9_.:-]{1,100}$/);
const byteCountSchema = z.number().int().min(0).max(Number.MAX_SAFE_INTEGER);
const dateTimeSchema = z.string().datetime({ offset: true });

function exactAllowedUrl(
  value: string,
  origins: ReadonlySet<string>,
): boolean {
  try {
    const url = new URL(value);
    return (
      url.protocol === "https:" &&
      !url.username &&
      !url.password &&
      !url.hash &&
      origins.has(url.origin.toLowerCase())
    );
  } catch {
    return false;
  }
}

const paymentUrlSchema = z
  .string()
  .max(2_048)
  .refine(
    (value) =>
      exactAllowedUrl(value, getEnvironment().paymentUrlAllowedOrigins),
    "Payment URL origin is not allowed",
  );

const subscriptionUrlSchema = z
  .string()
  .max(4_096)
  .refine(
    (value) =>
      exactAllowedUrl(value, getEnvironment().subscriptionUrlAllowedOrigins),
    "Subscription URL origin is not allowed",
  );

const referralUrlSchema = z
  .string()
  .max(1_024)
  .refine((value) => {
    try {
      const url = new URL(value);
      return (
        url.protocol === "https:" &&
        url.hostname.toLowerCase() === "t.me" &&
        !url.port &&
        !url.username &&
        !url.password &&
        !url.hash &&
        url.pathname.toLowerCase() ===
          `/${getEnvironment().TELEGRAM_BOT_USERNAME.toLowerCase()}` &&
        /^ref_[1-9][0-9]{0,19}$/.test(url.searchParams.get("start") ?? "")
      );
    } catch {
      return false;
    }
  }, "Referral URL is invalid");

const tariffPeriodSchema = z
  .object({
    months: z.number().int().min(1).max(36),
    title: z.string().trim().min(1).max(80),
    amountRub: z.number().int().min(1).max(1_000_000),
  })
  .strict();

const tariffSchema = z
  .object({
    id: identifierSchema,
    title: z.string().trim().min(1).max(120),
    description: z.string().trim().min(1).max(500),
    purchaseEnabled: z.boolean(),
    trafficLimitBytes: byteCountSchema,
    deviceLimit: z.number().int().min(1).max(100),
    periods: z.array(tariffPeriodSchema).max(24),
  })
  .strict();

const paymentMethodSchema = z
  .object({
    id: identifierSchema,
    title: z.string().trim().min(1).max(120),
    feePercent: z.number().min(0).max(100),
  })
  .strict();

const addonSchema = z
  .object({
    id: z.enum(["slot_addon", "traffic_addon"]),
    title: z.string().trim().min(1).max(120),
    enabled: z.boolean(),
    amountRub: z.number().int().min(1).max(1_000_000),
    deviceDelta: z.number().int().min(0).max(100),
    trafficDeltaBytes: byteCountSchema,
  })
  .strict();

const deviceSchema = z
  .object({
    id: z.string().min(1).max(200),
    label: z.string().trim().min(1).max(160),
  })
  .strict();

const trafficUsageSchema = z
  .object({
    usedBytes: byteCountSchema,
    limitBytes: byteCountSchema,
  })
  .strict();

const deviceUsageSchema = z
  .object({
    used: z.number().int().min(0).max(100),
    limit: z.number().int().min(1).max(100),
    items: z.array(deviceSchema).max(100),
  })
  .strict();

const subscriptionComponentSchema = z
  .object({
    traffic: trafficUsageSchema,
    devices: deviceUsageSchema,
  })
  .strict();

export const bridgeOrderSchema = z
  .object({
    id: z.number().int().positive().max(Number.MAX_SAFE_INTEGER),
    kind: identifierSchema,
    status: identifierSchema,
    tariffId: identifierSchema.nullable(),
    months: z.number().int().min(0).max(36),
    amountRub: z.number().int().min(0).max(1_000_000),
    paymentMethodId: identifierSchema,
    createdAt: dateTimeSchema,
    paymentUrl: paymentUrlSchema.nullable(),
  })
  .strict();

const subscriptionSchema = z
  .object({
    uuid: z.string().uuid(),
    tariffId: identifierSchema,
    title: z.string().trim().min(1).max(160),
    status: identifierSchema,
    expireAt: dateTimeSchema.nullable(),
    subscriptionUrl: subscriptionUrlSchema.nullable(),
    traffic: trafficUsageSchema,
    devices: deviceUsageSchema,
    components: z
      .object({
        regular: subscriptionComponentSchema,
        mobile: subscriptionComponentSchema,
      })
      .strict()
      .optional(),
    shield: z
      .object({
        supported: z.boolean(),
        enabled: z.boolean(),
      })
      .strict(),
    actions: z
      .object({
        renew: z.boolean(),
        rotateKey: z.boolean(),
        revokeDevice: z.boolean(),
        slotAddon: z.boolean(),
        trafficAddon: z.boolean(),
      })
      .strict(),
  })
  .strict();

const catalogResponseSchema = z
  .object({
    ok: z.literal(true),
    tariffs: z.array(tariffSchema).max(50),
    paymentMethods: z.array(paymentMethodSchema).max(20),
    addons: z.array(addonSchema).max(20),
  })
  .strict();

const snapshotResponseSchema = z
  .object({
    ok: z.literal(true),
    user: bridgeUserSchema,
    trial: z
      .object({
        eligible: z.boolean(),
        status: identifierSchema,
        expiresAt: dateTimeSchema.nullable(),
      })
      .strict(),
    referrals: z
      .object({
        invited: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
        rewarded: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
        discountPercent: z.number().int().min(0).max(100),
        rewardDays: z.number().int().min(0).max(3650),
        referralLink: referralUrlSchema,
      })
      .strict()
      .nullable(),
    subscriptions: z.array(subscriptionSchema).max(100),
    orders: z.array(bridgeOrderSchema).max(20),
    freeProxy: z
      .object({
        available: z.boolean(),
        active: z.boolean(),
      })
      .strict(),
  })
  .strict();

const createOrderResponseSchema = z
  .object({
    ok: z.literal(true),
    order: bridgeOrderSchema,
  })
  .strict();

const orderStatusResponseSchema = createOrderResponseSchema;

const simpleMutationResponseSchema = z
  .object({
    ok: z.literal(true),
    subscriptionUuid: z.string().uuid(),
  })
  .passthrough();

const freeProxyResponseSchema = z
  .object({
    ok: z.literal(true),
    proxy: z
      .object({
        label: z.string().trim().min(1).max(160),
        url: z
          .string()
          .max(2_048)
          .refine((value) => {
            try {
              const url = new URL(value);
              const keys = [...url.searchParams.keys()];
              return (
                url.protocol === "tg:" &&
                url.hostname === "proxy" &&
                url.pathname === "" &&
                !url.username &&
                !url.password &&
                !url.hash &&
                keys.length === 3 &&
                new Set(keys).size === 3 &&
                ["server", "port", "secret"].every((key) =>
                  url.searchParams.has(key),
                )
              );
            } catch {
              return false;
            }
          }, "Proxy URL is invalid"),
        rateLimitMbps: z.number().int().min(1).max(100_000),
        deviceLimit: z.number().int().min(1).max(100),
      })
      .strict(),
  })
  .strict();

const activateTrialResponseSchema = z
  .object({
    ok: z.literal(true),
    subscriptionUuid: z.string().uuid(),
  })
  .strict();

export type BridgeCatalog = z.output<typeof catalogResponseSchema>;
export type BridgeSnapshot = z.output<typeof snapshotResponseSchema>;
export type BridgeOrder = z.output<typeof bridgeOrderSchema>;

export type CreateBridgeOrderInput =
  | {
      kind: "access_purchase";
      tariffId: string;
      months: number;
      paymentMethodId: string;
    }
  | {
      kind: "access_renewal";
      subscriptionUuid: string;
      tariffId?: string;
      months: number;
      paymentMethodId: string;
    }
  | {
      kind: "slot_addon" | "traffic_addon";
      subscriptionUuid: string;
      paymentMethodId: string;
    };

export async function getBridgeCatalog(grant: string): Promise<BridgeCatalog> {
  return bridgeCall("/catalog", {}, catalogResponseSchema, { grant });
}

export async function getBridgeSnapshot(
  grant: string,
): Promise<BridgeSnapshot> {
  return bridgeCall("/account/snapshot", {}, snapshotResponseSchema, { grant });
}

export async function createBridgeOrder(
  grant: string,
  input: CreateBridgeOrderInput,
  idempotencyKey: string,
): Promise<BridgeOrder> {
  const response = await bridgeCall(
    "/orders/create",
    input,
    createOrderResponseSchema,
    { grant, idempotencyKey },
  );
  return response.order;
}

export async function getBridgeOrderStatus(
  grant: string,
  orderId: number,
): Promise<BridgeOrder> {
  const response = await bridgeCall(
    "/orders/status",
    { orderId },
    orderStatusResponseSchema,
    { grant },
  );
  return response.order;
}

export async function revokeBridgeDevice(
  grant: string,
  input: { subscriptionUuid: string; deviceId: string },
  idempotencyKey: string,
): Promise<void> {
  await bridgeCall(
    "/devices/revoke",
    input,
    simpleMutationResponseSchema.extend({
      deviceId: z.string().min(1).max(200),
    }),
    { grant, idempotencyKey },
  );
}

export async function rotateBridgeSubscriptionKey(
  grant: string,
  subscriptionUuid: string,
  idempotencyKey: string,
): Promise<string | null> {
  const response = await bridgeCall(
    "/subscriptions/rotate-key",
    { subscriptionUuid },
    simpleMutationResponseSchema.extend({
      subscriptionUrl: subscriptionUrlSchema.nullable(),
    }),
    { grant, idempotencyKey },
  );
  return response.subscriptionUrl;
}

export async function setBridgeSubscriptionShield(
  grant: string,
  input: { subscriptionUuid: string; enabled: boolean },
  idempotencyKey: string,
): Promise<boolean> {
  const response = await bridgeCall(
    "/subscriptions/shield",
    input,
    simpleMutationResponseSchema.extend({
      shieldEnabled: z.boolean(),
    }),
    { grant, idempotencyKey },
  );
  return response.shieldEnabled;
}

export async function claimBridgeFreeProxy(
  grant: string,
  idempotencyKey: string,
): Promise<z.output<typeof freeProxyResponseSchema>["proxy"]> {
  const response = await bridgeCall(
    "/free-proxy",
    {},
    freeProxyResponseSchema,
    { grant, idempotencyKey },
  );
  return response.proxy;
}

export async function activateBridgeTrial(
  grant: string,
  idempotencyKey: string,
): Promise<string> {
  const response = await bridgeCall(
    "/trial/activate",
    {},
    activateTrialResponseSchema,
    { grant, idempotencyKey },
  );
  return response.subscriptionUuid;
}
