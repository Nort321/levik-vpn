export type ProtectionState = "protected" | "direct" | "unknown";

export type IpRegistration = {
  network: string;
  registry: string;
  name: string | null;
  handle: string | null;
  type: string | null;
  countryCode: string | null;
  rangeStart: string;
  rangeEnd: string;
  registeredAt: string | null;
  updatedAt: string | null;
};

export type IpCheckSnapshot = {
  ip: string;
  version: "IPv4" | "IPv6";
  country: string | null;
  countryCode: string | null;
  region: string | null;
  city: string | null;
  timezone: string | null;
  flagEmoji: string | null;
  provider: string | null;
  organization: string | null;
  asn: number | null;
  registration: IpRegistration | null;
  protection: ProtectionState;
  checkedAt: string;
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === "string";
}

function isIpRegistration(value: unknown): value is IpRegistration {
  return (
    isRecord(value) &&
    typeof value.network === "string" &&
    typeof value.registry === "string" &&
    isNullableString(value.name) &&
    isNullableString(value.handle) &&
    isNullableString(value.type) &&
    isNullableString(value.countryCode) &&
    typeof value.rangeStart === "string" &&
    typeof value.rangeEnd === "string" &&
    isNullableString(value.registeredAt) &&
    isNullableString(value.updatedAt)
  );
}

export function isIpCheckSnapshot(value: unknown): value is IpCheckSnapshot {
  return (
    isRecord(value) &&
    typeof value.ip === "string" &&
    (value.version === "IPv4" || value.version === "IPv6") &&
    (value.registration === null || isIpRegistration(value.registration)) &&
    (value.protection === "protected" ||
      value.protection === "direct" ||
      value.protection === "unknown") &&
    typeof value.checkedAt === "string"
  );
}
