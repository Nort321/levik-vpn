import type { MonitorUserCountrySummary } from "@/lib/monitor/types";

const countryCentroids: Readonly<Record<string, readonly [number, number]>> = {
  AR: [-64, -34], AT: [14, 47], AU: [134, -25], BE: [4, 51], BG: [25, 43],
  BR: [-52, -10], BY: [28, 54], CA: [-107, 57], CH: [8, 47], CL: [-71, -33],
  CN: [104, 35], CZ: [15, 50], DE: [10, 51], DK: [10, 56], EE: [26, 59],
  ES: [-4, 40], FI: [26, 64], FR: [2, 46], GB: [-3, 55], GE: [44, 42],
  GR: [22, 39], HU: [19, 47], ID: [118, -2], IE: [-8, 53], IL: [35, 31],
  IN: [79, 22], IT: [12, 42], JP: [138, 37], KG: [75, 41], KZ: [68, 48],
  LT: [24, 56], LV: [25, 57], MD: [29, 47], MX: [-102, 23], NL: [5, 52],
  NO: [10, 62], NZ: [174, -41], PL: [20, 52], PT: [-8, 39], RO: [25, 46],
  RS: [21, 44], RU: [88, 61], SE: [16, 62], SK: [20, 49], TH: [101, 15],
  TR: [35, 39], UA: [32, 49], US: [-99, 39], UZ: [64, 41], VN: [108, 16],
  ZA: [25, -29],
};

function countryName(countryCode: string): string {
  return new Intl.DisplayNames(["ru"], { type: "region" }).of(countryCode) ?? countryCode;
}

function signalState(successRate: number): "ok" | "warning" | "error" {
  if (successRate >= 95) return "ok";
  if (successRate >= 75) return "warning";
  return "error";
}

function project([longitude, latitude]: readonly [number, number]) {
  return {
    x: ((longitude + 180) / 360) * 1_000,
    y: ((90 - latitude) / 180) * 480,
  };
}

export function UserCheckMap({
  countries,
  serviceName,
}: {
  countries: readonly MonitorUserCountrySummary[];
  serviceName: string;
}) {
  const mappedCountries = countries.flatMap((country) => {
    const centroid = countryCentroids[country.countryCode];
    return centroid ? [{ ...country, ...project(centroid) }] : [];
  });

  return (
    <article className="glow-panel monitor-user-map">
      <div className="monitor-user-map__heading">
        <div>
          <span>Карта пользовательских проверок</span>
          <h3>Где проверяли {serviceName}</h3>
        </div>
        <p>Только страны, где за 15 минут получено не менее 10 проверок.</p>
      </div>
      <div className="monitor-user-map__content">
        <div className="monitor-user-map__canvas">
          <svg
            aria-label={`Карта доступности ${serviceName} по пользовательским проверкам`}
            role="img"
            viewBox="0 0 1000 480"
          >
            <g className="monitor-user-map__land">
              <path d="M58 103 116 61 204 55 260 90 284 137 250 172 203 165 169 205 130 188 106 146 72 140Z" />
              <path d="m237 228 54 22 38 63-15 89-42 65-28-72 8-71-27-52Z" />
              <path d="m421 93 55-35 99 10 47 36-32 31-77-2-29 25-55-15Z" />
              <path d="m455 166 68-24 61 35 24 91-52 120-48-22-18-73-40-54Z" />
              <path d="m580 91 96-40 154 27 109 77-43 62-85-8-68 42-71-29-30-55-73-26Z" />
              <path d="m789 327 73-35 91 42-22 66-85 17-61-42Z" />
            </g>
            {mappedCountries.map((country) => (
              <g
                className={`monitor-user-map__point monitor-user-map__point--${signalState(country.successRate)}`}
                key={country.countryCode}
                transform={`translate(${country.x} ${country.y})`}
              >
                <title>
                  {countryName(country.countryCode)}: {country.successRate}% успешных,
                  {" "}{country.totalChecks} проверок
                </title>
                <circle className="monitor-user-map__pulse" r="17" />
                <circle r="7" />
              </g>
            ))}
          </svg>
          {countries.length === 0 ? (
            <div className="monitor-user-map__empty">
              <strong>Карта появится после накопления данных</strong>
              <span>Сейчас ни по одной стране нет достаточной выборки.</span>
            </div>
          ) : null}
        </div>
        <ul className="monitor-user-map__countries">
          {countries.map((country) => (
            <li key={country.countryCode}>
              <span
                aria-hidden="true"
                className={`monitor-signal-dot monitor-signal-dot--${signalState(country.successRate)}`}
              />
              <div>
                <strong>{countryName(country.countryCode)}</strong>
                <small>{country.totalChecks} проверок</small>
              </div>
              <b>{country.successRate}%</b>
            </li>
          ))}
        </ul>
      </div>
    </article>
  );
}
