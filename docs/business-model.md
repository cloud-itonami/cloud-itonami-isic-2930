# Business Model: Manufacture of Parts and Accessories for Motor Vehicles

## Classification
- Repository: `cloud-itonami-isic-2930`
- ISIC Rev.5: `2930` — manufacture of parts and accessories for motor vehicles — Tier-1/Tier-2 part-lot production, PPAP evidence verification and PPAP-certificate issuance
- Social impact: vehicle-safety, supply-resilience, industrial-jobs

## Customer
- independent Tier-1/Tier-2 auto-parts manufacturers needing auditable PPAP and production records
- contract plants producing parts or sub-assemblies (brake pads, wiring harnesses, seats, stampings, fasteners) for multiple OEMs
- plant operators needing verifiable build and process-capability history for produced part-lots
- OEM supplier-quality auditors needing verifiable PPAP evidence and conformance records
- programs that cannot accept closed, unauditable manufacturing-execution platforms

## Offer
- PPAP (Production Part Approval Process) evidence checklist and jurisdiction-scope version management
- robotics-assisted dimensional inspection, torque check and weld/joint inspection records
- part-lot DPPM (defective parts per million) and critical-dimension-deviation history
- PPAP-certificate drafts and disclosure records
- role-based access and immutable audit ledger
- CSV/EDN audit package export for OEM auditors

## Revenue
- self-host setup fee
- managed hosting subscription per plant / production line
- support retainer with SLA
- inspection-cell robot integration and maintenance

## Trust Controls
- out-of-spec part-lots are blocked; a PPAP certificate is mandatory for shipment paths; part-lot history is immutable
- a robot action the governor refuses is never dispatched to hardware
- every shipment, hold, approval and disclosure path is auditable
- sensitive design and production data stays outside Git
- a fabricated PPAP-evidence citation, incomplete evidence, an
  out-of-spec DPPM reject rate, or an unresolved process-capability
  defect -- each forces a hold, not an override
- PPAP-certificate issuance is logged and escalated, and cannot be
  finalized twice for the same part-lot
