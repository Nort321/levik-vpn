import "server-only";

import { z } from "zod";

import { bridgeUserSchema } from "@/lib/server/bridge/auth";
import { bridgeCall } from "@/lib/server/bridge/core";

const accountBridgeAuthorizationSchema = z
  .object({
    ok: z.literal(true),
    grant: z.string().regex(/^[A-Za-z0-9_-]{32,256}$/),
    grantExpiresIn: z.number().int().min(60).max(31 * 24 * 60 * 60),
    user: bridgeUserSchema,
  })
  .strict();

export type AccountBridgeAuthorization = z.output<
  typeof accountBridgeAuthorizationSchema
>;

export async function issueAccountBridgeGrant(input: {
  accountId: string;
  legacyUserKey: string | null;
  idempotencyKey: string;
}): Promise<AccountBridgeAuthorization> {
  return bridgeCall(
    "/auth/account/grant",
    {
      accountId: input.accountId,
      legacyUserKey: input.legacyUserKey,
    },
    accountBridgeAuthorizationSchema,
    { idempotencyKey: input.idempotencyKey },
  );
}
