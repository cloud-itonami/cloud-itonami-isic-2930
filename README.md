# cloud-itonami-isic-2930

Open Business Blueprint for **ISIC Rev.5 2930**: manufacture of
parts and accessories for motor vehicles -- Tier-1/Tier-2 part-lot
intake, PPAP evidence verification, process-capability defect
screening, robot dimensional/torque/weld inspection and PPAP-
certificate finalization for a community auto-parts plant.

This repository publishes an auto-parts-manufacturing actor --
part-lot intake, per-jurisdiction PPAP (Production Part Approval
Process) evidence-checklist verification, process-capability-defect
screening, robot part-lot shipment and PPAP-certificate issuance --
as an OSS business that any qualified Tier-1/Tier-2 auto-parts
manufacturer can fork, deploy, run, improve and sell, so a plant
keeps its own production and quality-approval history instead of
renting a closed MES / quality SaaS.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet -- here it is **Auto-Parts Advisor ⊣
Auto-Parts Governor**.

## Scope note: parts tier, not final assembly

This repository is scoped to **manufacturing parts and accessories**
for motor vehicles (a Tier-1/Tier-2 supplier producing brake pads,
wiring harnesses, seats, stampings, fasteners, etc. for shipment to
an OEM). It is not the final-assembly vertical. Distinct from:

- `cloud-itonami-isic-2910` — manufacture of motor vehicles (OEM
  final assembly, vehicle type-approval/homologation, Certificate of
  Conformity)
- `cloud-itonami-isic-2410` — basic iron and steel **manufacturing**
- `cloud-itonami-isic-2811` — engines and turbines **manufacturing**
- `cloud-itonami-isic-3011` — ships and floating structures
  **manufacturing**

ISIC 2910 covers the vehicle final-assembly tier; ISIC 2930 covers
the auto-parts supplier tier one step upstream in the same value
chain -- a supplier ships a part-lot to an OEM assembly plant, which
is the customer relationship this actor's `:actuation/ship-part-lot`
models.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here robots (CMM dimensional
scan, fastener-torque check, weld/joint ultrasonic scan) operate under
an actor that proposes actions and an independent **Auto-Parts
Governor** that gates them. The governor never issues a PPAP
certificate itself; `:high`/`:safety-critical` actions
(`:actuation/ship-part-lot`, `:actuation/issue-ppap-certificate`)
require human sign-off.

**Robot process simulation is concrete, not just a flag** (ADR-2607142800,
extending ADR-2607011000, which named this vertical, isic-2930, as
explicit follow-up work): `autoparts.robotics` walks every part-lot
through a robot-executed dimensional/process-capability verification
mission (`kotoba.robotics` mission/action/telemetry-proof contracts)
-- CMM dimensional scan, fastener-torque check, weld-joint ultrasonic
scan -- before `:actuation/ship-part-lot` is proposable. The
Auto-Parts Governor independently re-derives the part-lot's own
critical-dimension-deviation tolerance from ground-truth fields, never
trusting the mission's self-reported verdict alone.

## Core contract

```text
part-lot intake + PPAP evidence verify + process-capability screen
  -> Auto-Parts Advisor proposal
  -> Auto-Parts Governor (HARD holds un-overridable)
  -> phase gate (actuation always escalates)
  -> human approval for high stakes
  -> append-only ledger + draft records
```

## Actuation honesty

Shipping a part-lot via a robot handling/dispatch action and issuing
a PPAP certificate produce **unsigned draft records and ledger facts
only**. This actor does not talk to real plant control systems or
OEM supplier portals. Signature and hardware dispatch are the
auto-parts plant's own acts.

## Ops

| Op | Effect |
|---|---|
| `:part-lot/intake` | normalize part-lot directory patch (phase 3 may auto-commit when clean) |
| `:ppap-evidence/verify` | per-jurisdiction PPAP evidence checklist (always human) |
| `:process-capability/screen` | process-capability defect screen (HARD hold if unresolved) |
| `:robotics/simulate-inspection-cell` | robot CMM/torque/weld-inspection verification mission (always human; required on file before shipment) |
| `:actuation/ship-part-lot` | draft part-lot-shipment record (always human; HARD hold if robotics-sim missing, independently out-of-tolerance, or DPPM out of range) |
| `:actuation/issue-ppap-certificate` | draft PPAP-certificate record (always human) |

## Social / regulatory hand-off

```clojure
(require '[autoparts.store :as store]
         '[autoparts.export :as export])

(def db (store/seed-db))
(export/audit-package db)           ;; EDN maps for OEM/quality-audit hand-off
(export/package->csv-bundle db)     ;; CSV bundle (part-lots/ledger/shipments/ppap-certificates)
```

Operator console (static sample): `docs/samples/operator-console.html`.

## Develop

```bash
clojure -M:dev:test
clojure -M:lint
clojure -M:dev:run
```

## License

AGPL-3.0-or-later — see `LICENSE`.

## Operator console (Pages)

After enabling GitHub Pages (Settings → Pages → GitHub Actions), the
static console is at:

https://cloud-itonami.github.io/cloud-itonami-isic-2930/

Local: open `docs/index.html` or `docs/samples/operator-console.html`.

## Export audit package (CLI)

```bash
clojure -M:dev:export
# or: clojure -M:dev:export /tmp/audit-2930
```

Writes CSV files under `out/audit-package/` (or the given directory).
