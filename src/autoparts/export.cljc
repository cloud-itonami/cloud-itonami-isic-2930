(ns autoparts.export
  "Audit-package export for social / regulatory hand-off.

  Produces plain EDN maps and CSV strings over a `autoparts.store/Store`
  snapshot -- the same append-only ledger, part-lot-shipment drafts and
  PPAP-certificate drafts the governor writes. Pure data transforms
  only: no I/O, no network, no signature. The manufacturer's own act
  is to sign and file the package; this namespace only materializes
  the package body.

  This is the honest delivery of the industry-stack `:export?` contract
  (robotics / audit-ledger capabilities) for ISIC 2930.

  `pedigree-for-part-lot` (ADR-2607999960, the second applied link of
  the ADR-2607999950 cross-actor supply-chain-linkage pattern: THIS
  actor issuing its OWN pedigree, for `cloud-itonami-isic-2910` to
  independently re-verify) is a SEPARATE kind of export, same shape
  and same discipline as `steelworks.export/pedigree-for-heat`: a
  pure data transform over data already on file, never a live
  network call and never an invented claim."
  (:require [clojure.string :as str]
            [kotoba.pedigree :as pedigree]
            [autoparts.store :as store]))

(defn- csv-escape [v]
  (let [s (str (if (nil? v) "" v))]
    (if (re-find #"[,\"\n\r]" s)
      (str "\"" (str/replace s "\"" "\"\"") "\"")
      s)))

(defn- csv-row [cols]
  (str/join "," (map csv-escape cols)))

(defn ledger-rows
  "Normalize ledger facts into flat row maps suitable for CSV."
  [st]
  (mapv (fn [i f]
          {:seq i
           :t (:t f)
           :op (str (:op f))
           :actor (:actor f)
           :subject (:subject f)
           :disposition (str (:disposition f))
           :basis (pr-str (:basis f))
           :summary (:summary f)})
        (range)
        (store/ledger st)))

(defn shipment-rows [st]
  (mapv (fn [i r]
          {:seq i
           :record_id (get r "record_id")
           :kind (get r "kind")
           :part_lot_id (get r "part_lot_id")
           :jurisdiction (get r "jurisdiction")})
        (range)
        (store/shipment-history st)))

(defn certificate-rows [st]
  (mapv (fn [i r]
          {:seq i
           :record_id (get r "record_id")
           :kind (get r "kind")
           :part_lot_id (get r "part_lot_id")
           :jurisdiction (get r "jurisdiction")})
        (range)
        (store/certificate-history st)))

(defn part-lots-snapshot [st]
  (mapv (fn [b]
          (select-keys b [:id :part-lot-name :jurisdiction :status
                          :dppm-actual
                          :dppm-min
                          :dppm-max
                          :process-capability-defect-unresolved?
                          :part-lot-shipped?
                          :ppap-certified?
                          :shipment-number
                          :certificate-number]))
        (store/all-part-lots st)))

(defn audit-package
  "Full audit package for a store snapshot -- the body an auto-parts
  manufacturer would hand to OEM quality auditors, market-regulator
  inspectors or internal compliance. `:format` is always `:edn-maps`
  for the nested package; use `package->csv-bundle` for CSV strings."
  [st]
  {:isic "2930"
   :business-id "cloud-itonami-isic-2930"
   :format :edn-maps
   :part-lots (part-lots-snapshot st)
   :ledger (vec (store/ledger st))
   :shipments (vec (store/shipment-history st))
   :ppap-certificates (vec (store/certificate-history st))
   :counts {:part-lots (count (store/all-part-lots st))
            :ledger (count (store/ledger st))
            :shipments (count (store/shipment-history st))
            :ppap-certificates (count (store/certificate-history st))}})

(defn rows->csv
  "Render a seq of flat maps as CSV using `header` column order."
  [header rows]
  (let [lines (into [(csv-row (map name header))]
                    (map (fn [r] (csv-row (map #(get r %) header))) rows))]
    (str (str/join "\n" lines) (when (seq lines) "\n"))))

(defn package->csv-bundle
  "CSV bundle for spreadsheet hand-off. Keys are filenames; values are
  CSV body strings."
  [st]
  {"part-lots.csv" (rows->csv [:id :part-lot-name :jurisdiction :status
                            :dppm-actual
                            :part-lot-shipped? :ppap-certified?
                            :shipment-number :certificate-number]
                           (part-lots-snapshot st))
   "ledger.csv" (rows->csv [:seq :t :op :actor :subject :disposition :basis :summary]
                           (ledger-rows st))
   "shipments.csv" (rows->csv [:seq :record_id :kind :part_lot_id :jurisdiction]
                               (shipment-rows st))
   "ppap-certificates.csv" (rows->csv [:seq :record_id :kind :part_lot_id :jurisdiction]
                                   (certificate-rows st))})

#?(:clj
(defn write-csv-bundle!
  "Write `package->csv-bundle` files under `dir` (created if missing).
  Returns the absolute path of `dir`. JVM-only I/O seam for social
  hand-off scripts; pure package construction stays in `package->csv-bundle`."
  [st dir]
  (let [d (java.io.File. (str dir))
        _ (.mkdirs d)
        bundle (package->csv-bundle st)]
    (doseq [[name body] bundle]
      (spit (java.io.File. d (str name)) body))
    (.getAbsolutePath d))))

;; ---------------------------------------------------------------------------
;; Cross-actor supply-chain-linkage export (ADR-2607999960)
;; ---------------------------------------------------------------------------

(defn pedigree-for-part-lot
  "Builds a `kotoba.pedigree` record (ADR-2607999960's second applied
  link of the ADR-2607999950 cross-actor supply-chain-linkage pattern,
  isic-2930 -> isic-2910) for `part-lot`, a part-lot record that
  ALREADY carries its own real, already-simulated weld-joint/fastener
  proof-load pull-test telemetry on file (`:sim-proof-load-force`,
  from `autoparts.robotics/pull-test-telemetry-for` -- ADR-2607152000's
  real `physics-2d` time-stepped joint proof-load pull-test
  simulation). This fn does NOT run that simulation itself -- it only
  packages a reading already on the part-lot map, mirroring how
  `steelworks.export/pedigree-for-heat` only ever materializes a
  package body over data already on file, never computes new
  evidence.

  `issued-at` (an ISO date string) is a caller-supplied argument, not
  a wall-clock read -- this fn stays pure/deterministic, the same
  discipline `pedigree-for-heat` already establishes.

  `:pedigree/claims` reports `:proof-load-force-n` -- a FORCE reading
  in Newtons, honestly named after `autoparts.robotics`'s own
  `:sim-proof-load-force` field (see that ns's docstring: force =
  mass x deceleration, a real load-cell-equivalent reading, not a
  stress figure this actor has no cross-sectional-area/stress model
  to derive). `:pedigree/evidence-basis` cites the real simulation
  function that derived the reading, never a self-reported checklist
  string.

  Genuine 2-hop chaining (ADR-2607999960): when `part-lot` itself
  already carries an `:upstream-pedigree` (a `kotoba.pedigree` record
  an upstream `cloud-itonami-isic-2410` steel heat issued via
  `steelworks.export/pedigree-for-heat`, and this actor's OWN governor
  independently re-verified before ever letting the part-lot ship --
  see `autoparts.governor`'s `upstream-pedigree-claims-out-of-
  tolerance-violations`), it is embedded here as `:pedigree/upstream`
  (`kotoba.pedigree/claim`'s `:upstream` option, ADR-2607999960),
  producing a genuine two-hop provenance chain (steel heat -> part
  lot) `cloud-itonami-isic-2910`'s own governor can independently
  re-verify shape-wise via `kotoba.pedigree/valid?`'s recursive check
  -- never a bare id the receiver has to go look up, and never a
  second network fetch. When `part-lot` carries no `:upstream-
  pedigree`, `:pedigree/upstream` is simply omitted -- a single-hop
  part-lot pedigree is unaffected by this option's existence.

  Returns nil (never a fabricated pedigree) when `part-lot` carries no
  real `:sim-proof-load-force` on file -- the SAME disclosed 'missing
  telemetry != inventable' discipline `autoparts.robotics` ns
  docstring / `proof-load-out-of-tolerance?` already establish."
  [{:keys [id sim-proof-load-force upstream-pedigree]} issued-at]
  (when (and id (number? sim-proof-load-force))
    (pedigree/claim
     (str "PEDIGREE-" id) id "cloud-itonami-isic-2930"
     {:proof-load-force-n sim-proof-load-force}
     :evidence-basis ["autoparts.robotics/run-pull-test (physics-2d time-stepped rigid-body simulation, weld-joint/fastener proof-load pull test -- see ns docstring)"]
     :issued-at issued-at
     :upstream upstream-pedigree)))
