# R19 — 🟠 K8s: MCAUSER('app') missing from IBM MQ channel definition

## Problem
`infra/docker/config.mqsc:8` defines the channel with `MCAUSER('app')`:
```
DEFINE CHANNEL(DEV.APP.SVRCONN) CHLTYPE(SVRCONN) TRPTYPE(TCP) MCAUSER('app') REPLACE
```

`infra/k8s/infra/ibmmq/configmap-mqsc.yaml:17` omits it:
```
DEFINE CHANNEL(DEV.APP.SVRCONN) CHLTYPE(SVRCONN) TRPTYPE(TCP) REPLACE
```

With `CHLAUTH(DISABLED)`, the missing `MCAUSER` means the channel adopts the client's asserted OS
user (or the MQ default), which differs between Docker Compose (where the fix was needed to unblock
connection) and OpenShift. This inconsistency could cause runtime authentication differences and
makes the security posture of the K8s deployment different from the tested local dev environment.

## File to change
- `infra/k8s/infra/ibmmq/configmap-mqsc.yaml`

## Fix
Add `MCAUSER('app')` to the channel definition in the K8s ConfigMap to match `config.mqsc`:
```
DEFINE CHANNEL(DEV.APP.SVRCONN) CHLTYPE(SVRCONN) TRPTYPE(TCP) MCAUSER('app') REPLACE
```

## Acceptance
- K8s MQ config matches docker `config.mqsc`
- Java services connect to IBM MQ on OpenShift without authentication errors
