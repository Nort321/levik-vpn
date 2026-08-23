import { describe, expect, it } from "vitest";

import { GET as robots } from "@/app/robots.txt/route";
import { GET as sitemap } from "@/app/sitemap.xml/route";

function request(host: string): Request {
  return new Request(`https://${host}/`, {
    headers: { "x-forwarded-host": host },
  });
}

describe("host-specific SEO routes", () => {
  it("publishes an isolated robots policy for Levik Notes", async () => {
    const response = robots(request("note.leviknet.com"));
    const body = await response.text();

    expect(body).toContain("Allow: /");
    expect(body).toContain("Disallow: /api/");
    expect(body).toContain("Sitemap: https://note.leviknet.com/sitemap.xml");
    expect(body).not.toContain("/dashboard/");
  });

  it("lists only the canonical Notes home page in its sitemap", async () => {
    const response = sitemap(request("note.leviknet.com"));
    const body = await response.text();

    expect(body).toContain("<loc>https://note.leviknet.com/</loc>");
    expect(body).toContain("<lastmod>2026-08-14</lastmod>");
    expect(body).not.toContain("leviknet.com/status");
  });

  it("publishes canonical Monitor service pages without private APIs", async () => {
    const robotsResponse = robots(request("mon.leviknet.com"));
    const robotsBody = await robotsResponse.text();
    const sitemapResponse = sitemap(request("mon.leviknet.com"));
    const sitemapBody = await sitemapResponse.text();

    expect(robotsBody).toContain("Disallow: /api/");
    expect(robotsBody).toContain("Sitemap: https://mon.leviknet.com/sitemap.xml");
    expect(sitemapBody).toContain("<loc>https://mon.leviknet.com/discord</loc>");
    expect(sitemapBody).toContain("<loc>https://mon.leviknet.com/youtube</loc>");
    expect(sitemapBody).toContain("<loc>https://mon.leviknet.com/methodology</loc>");
    expect(sitemapBody).not.toContain("/api/");
  });
});
