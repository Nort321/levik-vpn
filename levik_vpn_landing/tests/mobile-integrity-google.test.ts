import {
  generateKeyPairSync,
  verify,
} from "node:crypto";

import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

import { GooglePlayIntegrityVerifier } from "@/lib/server/mobile-integrity-google";

describe("Google Play Integrity verifier", () => {
  it("authorizes with a signed service-account assertion and normalizes verdicts", async () => {
    const keyPair = generateKeyPairSync("rsa", {
      modulusLength: 2_048,
      privateKeyEncoding: {
        type: "pkcs8",
        format: "pem",
      },
      publicKeyEncoding: {
        type: "spki",
        format: "pem",
      },
    });
    const now = 1_800_000_000_000;
    const certificateDigest = Buffer.alloc(32, 23).toString("base64url");
    const fetchImplementation: typeof fetch = (input, init) => {
      const url = requestUrl(input);
      if (url === "https://oauth2.googleapis.com/token") {
        const parameters = new URLSearchParams(requestBody(init));
        const assertion = parameters.get("assertion");
        expect(parameters.get("grant_type")).toBe(
          "urn:ietf:params:oauth:grant-type:jwt-bearer",
        );
        expect(assertion).not.toBeNull();
        const segments = assertion?.split(".");
        if (!segments || segments.length !== 3) {
          throw new Error("Invalid test JWT assertion");
        }
        const [encodedHeader, encodedClaims, encodedSignature] = segments;
        expect(
          JSON.parse(
            Buffer.from(encodedHeader, "base64url").toString("utf8"),
          ),
        ).toEqual({ alg: "RS256", typ: "JWT" });
        expect(
          JSON.parse(
            Buffer.from(encodedClaims, "base64url").toString("utf8"),
          ),
        ).toMatchObject({
          iss: "integrity@example-project.iam.gserviceaccount.com",
          scope: "https://www.googleapis.com/auth/playintegrity",
          aud: "https://oauth2.googleapis.com/token",
          iat: now / 1_000,
          exp: now / 1_000 + 3_600,
        });
        expect(
          verify(
            "RSA-SHA256",
            Buffer.from(`${encodedHeader}.${encodedClaims}`, "ascii"),
            keyPair.publicKey,
            Buffer.from(encodedSignature, "base64url"),
          ),
        ).toBe(true);
        return Promise.resolve(
          jsonResponse({
            access_token: "google-access-token-value",
            expires_in: 3_600,
            token_type: "Bearer",
          }),
        );
      }

      expect(url).toBe(
        "https://playintegrity.googleapis.com/v1/com.leviknet.vpn:decodeIntegrityToken",
      );
      expect(
        new Headers(init?.headers).get("authorization"),
      ).toBe("Bearer google-access-token-value");
      expect(JSON.parse(requestBody(init))).toEqual({
        integrity_token: "encrypted-integrity-token",
      });
      return Promise.resolve(
        jsonResponse({
          tokenPayloadExternal: {
            requestDetails: {
              requestPackageName: "com.leviknet.vpn",
              requestHash: "bound-request-hash",
              timestampMillis: String(now),
            },
            appIntegrity: {
              appRecognitionVerdict: "PLAY_RECOGNIZED",
              packageName: "com.leviknet.vpn",
              certificateSha256Digest: [certificateDigest],
              versionCode: "1",
            },
            accountDetails: {
              appLicensingVerdict: "LICENSED",
            },
            deviceIntegrity: {
              deviceRecognitionVerdict: [
                "MEETS_BASIC_INTEGRITY",
                "MEETS_DEVICE_INTEGRITY",
              ],
            },
          },
        }),
      );
    };
    const fetchMock = vi.fn(fetchImplementation);
    const verifier = new GooglePlayIntegrityVerifier(
      "integrity@example-project.iam.gserviceaccount.com",
      keyPair.privateKey,
      fetchMock,
      () => now,
    );

    await expect(
      verifier.verify({
        integrityToken: "encrypted-integrity-token",
        expectedRequestHash: "bound-request-hash",
        expectedPackageName: "com.leviknet.vpn",
      }),
    ).resolves.toEqual({
      requestHash: "bound-request-hash",
      requestPackageName: "com.leviknet.vpn",
      appPackageName: "com.leviknet.vpn",
      certificateSha256Digests: [certificateDigest],
      appRecognized: true,
      licensed: true,
      meetsDeviceIntegrity: true,
      evaluatedAt: new Date(now),
    });

    await verifier.verify({
      integrityToken: "encrypted-integrity-token",
      expectedRequestHash: "bound-request-hash",
      expectedPackageName: "com.leviknet.vpn",
    });
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(
      fetchMock.mock.calls.filter(
        ([input]) =>
          requestUrl(input) === "https://oauth2.googleapis.com/token",
      ),
    ).toHaveLength(1);
  });
});

function jsonResponse(value: unknown): Response {
  return new Response(JSON.stringify(value), {
    status: 200,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
    },
  });
}

function requestUrl(input: RequestInfo | URL): string {
  if (typeof input === "string") {
    return input;
  }
  return input instanceof URL ? input.href : input.url;
}

function requestBody(init: RequestInit | undefined): string {
  if (typeof init?.body !== "string") {
    throw new Error("Expected a string request body");
  }
  return init.body;
}
