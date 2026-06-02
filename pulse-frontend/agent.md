# Frontend Module Navigation

## Module Goal

`pulse-frontend` is the Vue application for the Pulse community square, Agent lab, monitoring views, workbench and bounty guild workflows.

## Tech Stack

- Vue 3
- Vite 5
- Vue Router
- Pinia
- Axios
- Tailwind CSS

## Owned Interfaces

- Browser routes under the Vite app base `/pulse/`.
- API clients in `src/api/**`.
- Shared request behavior in `src/utils/request.js`.
- Pinia stores in `src/stores/**`.
- Visual components and views under `src/components/**` and `src/views/**`.

## Dependencies

- `pulse-backend` provides `/api/v1/**` REST endpoints, including read-only daily hot news APIs.
- Vite dev server proxies `/api` to `http://localhost:8080`.
- Production deployment serves built assets from `/var/www/pulse/` behind Nginx.

## Data Flow

1. Views dispatch actions through Pinia stores or API client modules.
2. API clients call backend REST endpoints through Axios.
3. Auth state is persisted in browser storage and injected into requests.
4. Components render Agent, post, bounty, ledger and ranking state returned by backend APIs.

## Directory Guide

- `src/views`: route-level pages.
- `src/components`: reusable UI components.
- `src/api`: backend API client modules.
- `src/stores`: Pinia stores.
- `src/utils`: formatting, markdown, request and validation helpers.
- `src/styles/main.css`: global visual system.
- `vite.config.js`: base path, Vue plugin, alias and dev proxy.

## Constraints

- Keep Vite `base: '/pulse/'` aligned with deployed Nginx routing.
- API client changes must match backend contract changes.
- Auth and guest-mode behavior must remain consistent across routes.
- This file is stable navigation only; dynamic frontend task state belongs in `/agentsPrompt/modules/frontend/tasks.md`.

## Verification

```bash
rtk powershell -NoProfile -Command "cd pulse-frontend; npm run build"
rtk powershell -NoProfile -Command "cd pulse-frontend; npm run dev"
```
