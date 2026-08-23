function requestHost(request: Request): string {
  return (request.headers.get("x-forwarded-host") ?? request.headers.get("host") ?? "")
    .split(":")[0]
    .toLowerCase();
}

const monitorServices = [
  "discord",
  "youtube",
  "telegram",
  "whatsapp",
  "steam",
  "github",
  "tiktok",
  "twitch",
] as const;

function entry(
  location: string,
  priority: string,
  changeFrequency: string,
  lastModified?: string,
): string {
  const lastmod = lastModified ? `<lastmod>${lastModified}</lastmod>` : "";
  return `<url><loc>${location}</loc>${lastmod}<changefreq>${changeFrequency}</changefreq><priority>${priority}</priority></url>`;
}

export function GET(request: Request) {
  const host = requestHost(request);
  const entries = host === "check.leviknet.com"
    ? [entry("https://check.leviknet.com/", "1.0", "weekly")]
    : host === "note.leviknet.com"
      ? [entry("https://note.leviknet.com/", "1.0", "weekly", "2026-08-14")]
      : host === "mon.leviknet.com"
        ? [
          entry("https://mon.leviknet.com/", "1.0", "always", "2026-08-15"),
          ...monitorServices.map((service) =>
            entry(`https://mon.leviknet.com/${service}`, "0.9", "always", "2026-08-15"),
          ),
          entry("https://mon.leviknet.com/methodology", "0.5", "monthly", "2026-08-15"),
        ]
      : [
        entry("https://leviknet.com/", "1.0", "weekly"),
        entry("https://leviknet.com/downloads", "0.8", "weekly"),
        entry("https://leviknet.com/status", "0.8", "hourly"),
        entry("https://leviknet.com/account/delete", "0.3", "monthly"),
        entry("https://leviknet.com/legal/privacy", "0.3", "yearly"),
        entry("https://leviknet.com/legal/terms", "0.3", "yearly"),
      ];
  const body = `<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">${entries.join("")}</urlset>`;

  return new Response(body, {
    headers: {
      "Cache-Control": "public, max-age=3600",
      "Content-Type": "application/xml; charset=utf-8",
    },
  });
}
