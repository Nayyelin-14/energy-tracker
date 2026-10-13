# Energy Tracker — Web View

Next.js 16 dashboard for the energy-tracker backend (API gateway on port 9000).

## Run

```bash
cp .env.example .env.local   # adjust API_BASE_URL if needed
npm install
npm run dev                  # http://localhost:3000
```

The dev server is ready before the backend — pages show a graceful
"gateway unreachable" state with a retry button until the stack is up.

## Pages

| Route        | Stitch screen                          | Backend calls |
|--------------|----------------------------------------|---------------|
| `/`          | overview_dashboard                      | user demo, devices, usage, series, coverage, alerts |
| `/devices`   | devices_management_inspection           | device CRUD   |
| `/alerts`    | alerts_audit_history                    | alert ledger  |
| `/insights`  | ai_insights_telemetric_intelligence     | providers, saving-tips (SSE), overview |
| `/simulation`| simulation_threshold_settings           | user threshold update, ingestion simulate |

`precision_telemetry` and `telemetry_kinetic` from the Stitch playground were
design systems rather than screens — their palette/type rules were merged into
the app-wide design system in `src/app/globals.css`.

## Architecture

- **API proxy:** `next.config.ts` rewrites `/backend/:path*` →
  `API_BASE_URL/:path*` (default `http://localhost:9000`). The gateway has no
  CORS config; the proxy keeps the browser same-origin.
- **Session:** `DemoSessionProvider` resolves `GET /api/v1/user/demo` once and
  shares the demo user with every page; 404 and offline states are first-class.
- **Design system:** warm graphite surfaces with hairline borders, one emerald
  accent plus amber/rose signals. Plus Jakarta Sans for prose, JetBrains Mono
  (tabular figures) for every numeric readout. Tokens live as CSS variables in
  `globals.css` and map to Tailwind utilities via `@theme inline`.
