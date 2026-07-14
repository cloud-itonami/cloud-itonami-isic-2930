(ns autoparts.registry
  "Pure-function part-lot-shipment + PPAP-certificate record
  construction -- an append-only auto-parts-manufacturer book-of-
  record draft.

  Like every sibling actor's registry, there is no single
  international check-digit standard for a part-lot-shipment or
  PPAP-certificate reference number -- every manufacturer/OEM assigns
  its own reference format. This namespace does NOT invent one; it
  builds a jurisdiction-scoped sequence number and validates the
  record's required fields, the same honest, non-fabricating
  discipline `autoparts.facts` uses.

  `part-lot-dppm-out-of-range?` continues this fleet's two-sided
  range check family (`testlab.registry/within-tolerance?` established
  the first, `conservation.registry/body-condition-out-of-range?` the
  second, `water.registry/contaminant-level-out-of-range?` the third,
  `steelworks.registry/heat-chemistry-out-of-range?`/`turbine.
  registry/unit-tolerance-out-of-range?`/`automotive.registry/vehicle-
  emissions-out-of-range?`/`automotive.robotics/structural-tolerance-
  out-of-range?` further siblings), applying the SAME lo/hi bounds-
  comparison shape to a part-lot's own measured defective-parts-per-
  million (DPPM) reject rate against the part-lot's own recorded
  quality-agreement bounds.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real plant/MES control system. It builds the RECORD a
  manufacturer would keep, not the act of shipping the part-lot robot
  action or issuing the PPAP certificate itself (that is `autoparts.
  operation`'s `:actuation/ship-part-lot`/`:actuation/issue-ppap-
  certificate`, always human-gated -- see README `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  manufacturer's own act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn part-lot-dppm-out-of-range?
  "Does `part-lot`'s own `:dppm-actual` (defective parts per million)
  fall outside its own `[:dppm-min :dppm-max]` recorded quality-
  agreement bounds? A pure ground-truth check against the part-lot's
  own permanent fields -- no upstream comparison needed. A further
  sibling in this fleet's two-sided range check family (see ns
  docstring)."
  [{:keys [dppm-actual dppm-min dppm-max]}]
  (and (number? dppm-actual) (number? dppm-min) (number? dppm-max)
       (or (< dppm-actual dppm-min)
           (> dppm-actual dppm-max))))

(defn register-part-lot-shipment
  "Validate + construct the PART-LOT-SHIPMENT registration DRAFT --
  the manufacturer's own act of dispatching a real robot shipment/
  handling action to release a part-lot to an OEM. Pure function --
  does not touch any real plant/MES control system; it builds the
  RECORD a manufacturer would keep. `autoparts.governor` independently
  re-verifies the part-lot's own DPPM sufficiency against its own
  quality-agreement bounds, and a double-shipment for the same
  part-lot, before this is ever allowed to commit."
  [part-lot-id jurisdiction sequence]
  (when-not (and part-lot-id (not= part-lot-id ""))
    (throw (ex-info "part-lot-shipment: part_lot_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "part-lot-shipment: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "part-lot-shipment: sequence must be >= 0" {})))
  (let [shipment-number (str (str/upper-case jurisdiction) "-SHP-" (zero-pad sequence 6))
        record {"record_id" shipment-number
                "kind" "part-lot-shipment-draft"
                "part_lot_id" part-lot-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "shipment_number" shipment-number
     "certificate" (unsigned-certificate "PartLotShipment" shipment-number shipment-number)}))

(defn register-ppap-certificate
  "Validate + construct the PPAP-CERTIFICATE registration DRAFT -- the
  manufacturer's own act of issuing a real PPAP submission /
  Certificate of Conformance certifying a part-lot as approved for
  production. Pure function -- does not touch any real plant/MES
  control system; it builds the RECORD a manufacturer would keep.
  `autoparts.governor` independently re-verifies the part-lot's own
  process-capability defect resolution status, and a double-issuance
  for the same part-lot, before this is ever allowed to commit."
  [part-lot-id jurisdiction sequence]
  (when-not (and part-lot-id (not= part-lot-id ""))
    (throw (ex-info "ppap-certificate: part_lot_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "ppap-certificate: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "ppap-certificate: sequence must be >= 0" {})))
  (let [certificate-number (str (str/upper-case jurisdiction) "-PPAP-" (zero-pad sequence 6))
        record {"record_id" certificate-number
                "kind" "ppap-certificate-draft"
                "part_lot_id" part-lot-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "certificate_number" certificate-number
     "certificate" (unsigned-certificate "PpapCertificate" certificate-number certificate-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
