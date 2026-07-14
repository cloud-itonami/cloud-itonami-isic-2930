# Operator Guide

## First Deployment
1. Register quality engineers, plants, part-lots, personnel and robots.
2. Import historical part-lot / process-capability / PPAP records.
3. Run read-only validation and robot mission dry-runs.
4. Configure PPAP evidence checklists and human sign-off paths.
5. Publish a dry-run audit export.

## Minimum Production Controls
- governor gate on every robot action before dispatch
- human sign-off for `:high`/`:safety-critical` robot actions (e.g. inspection-cell dimensional scan on safety-critical part-lots, PPAP-certificate issuance)
- audit export for every shipment, sign-off and disclosure
- backup manual process

## Certification
Certified operators must prove robot-safety integrity, evidence-backed
records and human review for safety-affecting actions.

## Operating states
intake : ppap-evidence-verify : process-capability-screen : approve : ship-part-lot : issue-ppap-certificate : audit

## Audit export (social operation)

After a production session, export the append-only package for
OEM quality auditors or internal compliance:

```clojure
(require '[autoparts.store :as store]
         '[autoparts.export :as export])
(export/audit-package store)        ; EDN maps
(export/package->csv-bundle store)  ; CSV files as string map
```

Drafts remain **unsigned** — signing and submission to an OEM supplier
portal are the auto-parts manufacturer's own acts (see README
Actuation honesty).

Static UI sample: `docs/samples/operator-console.html`.
