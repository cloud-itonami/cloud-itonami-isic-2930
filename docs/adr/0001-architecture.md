# ADR-0001: Auto-Parts Advisor ⊣ Auto-Parts Governor architecture

- Status: Accepted (2026-07-14)
- Repository: `cloud-itonami-isic-2930` (ISIC Rev.5 `2930`)

## Context

Auto-parts manufacturing (Tier-1/Tier-2 part-lot production,
process-capability inspection, PPAP evidence verification, PPAP-
certificate issuance) needs the same governed-actor pattern as the
rest of the cloud-itonami fleet: an untrusted advisor proposes; an
independent governor may HOLD; high-stakes actuation never
auto-commits.

A value-chain survey of the automotive industry (2026-07-14) found
`cloud-itonami-isic-2910` (motor-vehicle final assembly) implemented,
but the auto-parts/component tier (ISIC 2930) had no actor at all --
a gap in the middle of the supply chain: a Tier-1/Tier-2 supplier
ships a part-lot to the OEM final-assembly plant that isic-2910
models, but nothing modeled the supplier side. The industry-registry
entry for `2930` had sat at `:maturity :spec` with a dead
`gftdcojp/cloud-itonami-C2930` placeholder URL since ADR-2607011000.
ADR-2607142800 additionally named `isic-2930` explicitly as follow-up
work for its robotics-process-simulation pattern (established there
via `cloud-itonami-isic-2910`'s `automotive.robotics`).

## Decision

1. Namespaces live under `autoparts.*` with the standard
   facts / registry / store / governor / phase / advisor / operation / sim /
   robotics shape.
2. Entity is a **part-lot** (a production lot/batch of one part
   number), not a vehicle, aircraft assembly, hull block or steel
   heat.
3. Dual actuation on the same entity:
   - `:actuation/ship-part-lot` (robot part-lot shipment dispatch draft)
   - `:actuation/issue-ppap-certificate` (PPAP-certificate draft)
4. Double-actuation guards use dedicated booleans
   (`:part-lot-shipped?`, `:ppap-certified?`), never a status
   lifecycle (ADR-2607071320 / 6492 lesson).
5. `part-lot-dppm-out-of-range?` continues the fleet two-sided range
   check family (after testlab / conservation / water / steelworks /
   turbine / automotive's vehicle-emissions and structural-tolerance
   checks), applied here to a part-lot's own measured DPPM (defective
   parts per million) reject rate against its own recorded quality-
   agreement bounds.
6. `autoparts.robotics` delivers ADR-2607142800's robotics-process-
   simulation pattern from day one (not retrofitted): a CMM
   dimensional scan / fastener-torque check / weld-joint ultrasonic
   scan mission, `:robotics-sim-verified?` on the part-lot, and a
   governor HARD check (`robotics-simulation-violations`) that
   requires the mission on file AND independently re-derives
   out-of-tolerance from the part-lot's own critical-dimension-
   deviation fields, never trusting the mission's self-reported
   verdict.
7. Process-capability defect unresolved is evaluated unconditionally
   so `:process-capability/screen` itself can HARD-hold (parksafety
   ADR-2607071922 Decision 5 discipline, same as `automotive.
   governor`'s end-of-line-defect-unresolved check).
8. PPAP evidence catalog seeds USA (AIAG PPAP Manual) / DEU (VDA 2
   PPF) / GBR (SMMT/IATF 16949) / JPN (JAPIA/JASO) only; missing
   jurisdictions are uncovered, never fabricated. Unlike isic-2910's
   vehicle type-approval (government-mandated statute), PPAP is an
   OEM-customer-driven industry quality-management requirement
   (IATF 16949) -- the facts catalog cites standards bodies honestly
   as such, never inflating an industry requirement into a law.

## Consequences

(+) The auto-parts supplier tier gains a forkable OSS operating stack
with auditable governor holds, closing the mid-supply-chain gap the
2026-07-14 value-chain survey identified.
(+) Reuses langgraph + store dual-backend parity without new physics.
(+) Delivers ADR-2607142800's robotics-process-simulation pattern as
a native part of this actor's initial build, rather than a follow-up
retrofit.
(−) No physical plant digital-twin tick in this repo (follow-up
domain data is out of scope here).
(−) PPAP-authority coverage is a starting catalog (4 jurisdictions),
not exhaustive, and does not capture OEM-specific PPAP supplements.

## Related

- ADR-2607011000 (robotics premise + ISIC coverage)
- ADR-2607111600 (isic-2910 motor-vehicle promotion -- sibling
  architecture this repo mirrors)
- ADR-2607142800 (robotics-process-simulation fleet pattern -- named
  this vertical, isic-2930, as explicit follow-up work)
- Superproject fleet ADR for this promotion: `90-docs/adr/2607150100-
  cloud-itonami-isic-2930-autoparts.md`
- Sibling architecture: `cloud-itonami-isic-2910` docs/adr/0001
