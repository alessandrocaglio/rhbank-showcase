# T-SPA-01: Fix Keycloak initialization and routing

## Problem
- `main.js` never calls `keycloak.init()` → login button does nothing
- Router guard calls `useKeycloak()` per-navigation and never awaits init
- `keycloak.login()` fires on uninitialized instance → silent fail

## Files to change
- `apps/spa-mobile-app/src/main.js`
- `apps/spa-mobile-app/src/router/index.js`
- `apps/spa-mobile-app/src/composables/useKeycloak.js`

## Acceptance
- Clicking "Sign in with Keycloak" redirects to Keycloak login page
- After login, app lands on /dashboard
- Unauthenticated navigation to protected route → /login
