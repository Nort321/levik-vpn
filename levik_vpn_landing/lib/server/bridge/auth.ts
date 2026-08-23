import "server-only";

import { z } from "zod";

import { bridgeCall } from "@/lib/server/bridge/core";

export const bridgeUserSchema = z
  .object({
    userKey: z.string().regex(/^usr_[A-Za-z0-9_-]{20,80}$/),
    userLabel: z.string().trim().min(1).max(160),
    telegramUsername: z
      .string()
      .regex(/^@?[A-Za-z0-9_]{5,32}$/)
      .optional(),
    photoUrl: z
      .string()
      .max(1_024)
      .refine((value) => {
        try {
          const url = new URL(value);
          return (
            url.protocol === "https:" &&
            !url.username &&
            !url.password &&
            !url.hash
          );
        } catch {
          return false;
        }
      }, "Photo URL must be a secure https URL")
      .optional(),
  })
  .strict();

const createDeviceAuthorizationSchema = z
  .object({
    ok: z.literal(true),
    deviceCode: z.string().regex(/^[A-Za-z0-9_-]{32,180}$/),
    userCode: z.string().regex(/^[A-HJ-NP-Z2-9]{4,12}$/),
    verificationUri: z.string().url().max(512),
    verificationUriComplete: z.string().url().max(1024),
    expiresIn: z.number().int().min(60).max(600),
    interval: z.number().int().min(1).max(10),
  })
  .strict();

const pendingDeviceAuthorizationSchema = z
  .object({
    ok: z.literal(true),
    status: z.literal("authorization_pending"),
    interval: z.number().int().min(1).max(10),
  })
  .strict();

const authorizedDeviceAuthorizationSchema = z
  .object({
    ok: z.literal(true),
    status: z.literal("authorized"),
    grant: z.string().regex(/^[A-Za-z0-9_-]{32,256}$/),
    grantExpiresIn: z.number().int().min(60).max(31 * 24 * 60 * 60),
    user: bridgeUserSchema,
  })
  .strict();

const deviceAuthorizationStatusSchema = z.discriminatedUnion("status", [
  pendingDeviceAuthorizationSchema,
  authorizedDeviceAuthorizationSchema,
]);

const revokeGrantResponseSchema = z
  .object({
    ok: z.literal(true),
  })
  .strict();

export type DeviceAuthorization = z.output<
  typeof createDeviceAuthorizationSchema
>;
export type DeviceAuthorizationStatus = z.output<
  typeof deviceAuthorizationStatusSchema
>;

export async function createDeviceAuthorization(): Promise<DeviceAuthorization> {
  return bridgeCall(
    "/auth/device/create",
    {},
    createDeviceAuthorizationSchema,
  );
}

export async function getDeviceAuthorizationStatus(
  deviceCode: string,
): Promise<DeviceAuthorizationStatus> {
  return bridgeCall(
    "/auth/device/status",
    { deviceCode },
    deviceAuthorizationStatusSchema,
  );
}

export async function revokeBridgeGrant(
  grant: string,
  idempotencyKey: string,
): Promise<void> {
  await bridgeCall(
    "/auth/grant/revoke",
    {},
    revokeGrantResponseSchema,
    { grant, idempotencyKey },
  );
}
