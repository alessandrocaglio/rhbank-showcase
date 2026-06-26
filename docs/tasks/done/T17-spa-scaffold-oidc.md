# T17 · spa-mobile-app: Project Scaffold + Keycloak OIDC Integration

**Phase:** 6 — spa-mobile-app (Vue 3)
**Status:** todo
**Depends on:** T01

## Design Reference

Base the visual design on the existing RHBank app:
- **Live demo:** https://alessandrocaglio.github.io/rhbank/
- **Source code:** https://github.com/alessandrocaglio/rhbank

### Design System

| Token | Value |
|---|---|
| Primary / accent | `#ee0000` (Red Hat red) |
| Background (light) | `#f8f8f8` |
| Surface (card) | `#ffffff` |
| Text primary | `#000000` |
| Text secondary | `#6a6a6a` |
| Border | `#e0e0e0` |
| Dark background | `#1a1a1a` |
| Dark surface | `#2c2c2c` |
| Dark text | `#ffffff` |
| Dark text secondary | `#b0b0b0` |
| Dark border | `#444444` |
| Shadow | `0 4px 12px rgba(0,0,0,0.05)` |

**Font:** Google Fonts `Red Hat Display` (weights 400, 500, 700). Apply `-webkit-font-smoothing: antialiased`.

**Viewport:** Max-width `420px`, centered, box-shadow for phone-card feel.

**Dark mode:** `body.dark` class toggles CSS custom properties; `transition: background-color 0.3s ease, color 0.3s ease`.

### Layout Shell

```
┌─────────────────────────┐  ← AppHeader (logo + title + theme toggle)
│                         │
│   <router-view />       │  ← Page content
│                         │
└─────────────────────────┘  ← BottomNav (Home · Payments · History · Profile)
```

### AppHeader Component
- Red Hat hat SVG logo (30×30 px) + "Red Hat Bank" title
- Theme toggle (custom checkbox slider 40×20 px)
- Notification badge (red circle, abs-positioned)
- `border-bottom: 1px solid var(--border-color)`

### BottomNav Component
- 4 items: Home (house icon), Payments (credit-card icon), History (clock icon), Profile (user icon)
- Active item: color `#ee0000`
- `font-size: 0.8rem`, flex column per item, `border-top` with reversed shadow

### LoginPage (`/login` route)
- Full-height container, red (`#ee0000`) background, centered flex
- White card with Red Hat logo + "Red Hat Bank" + "WELCOME!" headline
- Transparent inputs with white underline borders only
- "Login with Keycloak" button: white bg, rounded (25 px), red text
- Clicking button calls `keycloak.login()`

## Deliverables

- `apps/spa-mobile-app/package.json` — deps: `vue@3`, `vue-router@4`, `keycloak-js@25.x`; devDeps: `vite@5`, `@vitejs/plugin-vue`, `vitest`, `@testing-library/vue`, `jsdom`, `@vue/test-utils`
- `vite.config.js` — Vue plugin, Vitest config (environment: jsdom, coverage: v8)
- `src/assets/main.css` — all CSS custom properties, body reset, Red Hat Display font import, `.dark` mode overrides, shared utility styles (card, button-primary, input-field)
- `src/config.js` — reads `window.__APP_CONFIG__`, falls back to `import.meta.env.VITE_*`:
  - `keycloakUrl`, `keycloakRealm`, `keycloakClientId`, `apiBaseUrl`
- `src/composables/useKeycloak.js` — wraps `keycloak-js`:
  - `init()` → `{ checkLoginIframe: false, onLoad: 'check-sso' }`
  - `login()`, `logout()`, `getToken()`
  - `onTokenExpired` → `keycloak.updateToken(30)`
  - Reactive: `isAuthenticated`, `username`
- `src/composables/useTheme.js` — `theme` ref (`'light'|'dark'`), `toggleTheme()` that sets `document.body.classList`; persists to `localStorage`
- `src/components/AppHeader.vue` — logo, title, theme toggle using `useTheme`
- `src/components/BottomNav.vue` — 4-item nav with inline SVG icons; active route highlighted red
- `src/views/LoginView.vue` — red full-screen login page (see design above)
- `src/router/index.js` — routes: `/login`, `/payments`, `/payments/:txId/status`; guard redirects unauthenticated users to `/login`
- `src/main.js`, `src/App.vue` (uses `AppHeader` + `BottomNav` shell around `<router-view>`)
- `nginx.conf` — `try_files $uri $uri/ /index.html`, gzip enabled
- `public/config.template.js` — `__KEYCLOAK_URL__`, `__KEYCLOAK_REALM__`, `__KEYCLOAK_CLIENT_ID__`, `__API_BASE_URL__` placeholders
- `docker-entrypoint.sh` — `envsubst` generates `config.js` from template at container start
- `Dockerfile` — multi-stage `node:20-alpine` build → `nginx:1.27-alpine` serve

## Unit Tests (`npm test` via Vitest)

`src/composables/__tests__/useKeycloak.spec.js`:
- Mock `keycloak-js`; assert `init()` called with `onLoad: 'check-sso'`
- Assert `getToken()` returns mocked token
- Assert `onTokenExpired` triggers `updateToken(30)`
- Assert `isAuthenticated` is `true` after successful init

`src/composables/__tests__/useTheme.spec.js`:
- `toggleTheme()` sets `document.body.classList` to `dark` when light; reverts to light on second call
- Theme persisted to `localStorage`

## Verification
```bash
cd apps/spa-mobile-app
npm install
npm test            # all spec files pass
npm run build       # dist/ created, no errors
docker build -t spa-test .   # image builds
```

## Acceptance Criteria
- [ ] `npm test` exits 0; all assertions pass
- [ ] `npm run build` exits 0
- [ ] `docker build` exits 0
- [ ] Design matches reference: Red Hat red, Red Hat Display font, 420px card layout, dark mode toggle
- [ ] `nginx.conf` includes SPA fallback rule
