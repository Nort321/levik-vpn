import "server-only";

function certificateFingerprint(digest: string): string {
  const decoded = Buffer.from(digest, "base64url");
  if (
    !/^[A-Za-z0-9_-]{43}$/.test(digest) ||
    decoded.byteLength !== 32 ||
    decoded.toString("base64url") !== digest
  ) {
    throw new Error("Android certificate digest is not canonical");
  }
  return decoded
    .toString("hex")
    .toUpperCase()
    .match(/.{2}/g)
    ?.join(":") ?? "";
}

export function buildAndroidAssetLinks(
  packageName: string,
  certificateDigests: ReadonlySet<string>,
) {
  if (certificateDigests.size === 0) {
    return [];
  }
  return [
    {
      relation: ["delegate_permission/common.handle_all_urls"],
      target: {
        namespace: "android_app",
        package_name: packageName,
        sha256_cert_fingerprints: [...certificateDigests]
          .sort()
          .map(certificateFingerprint),
      },
    },
  ];
}
