# T-SPA-02: Redesign layout components and views

## Files to change/create
- `apps/spa-mobile-app/src/App.vue` — hide header/nav on login route
- `apps/spa-mobile-app/src/components/AppHeader.vue` — Red Hat hat logo + logout
- `apps/spa-mobile-app/src/components/BottomNav.vue` — working routes
- `apps/spa-mobile-app/src/views/LoginView.vue` — Red Hat branding (red bg, hat logo)
- `apps/spa-mobile-app/src/views/DashboardView.vue` — NEW: balance card + send CTA

## Design reference: tmp/rhbank/
- Primary: #ee0000, font: Red Hat Display
- Login: red full-page, centered white card, hat SVG logo, pill login button
- Header: hat SVG (30px) + "Red Hat Bank" + logout/theme icons right
- Dashboard: red balance card, account number ACC-001, "Send Money" → /payments
