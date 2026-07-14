(ns autoparts.export
  "Audit-package export for social / regulatory hand-off.

  Produces plain EDN maps and CSV strings over a `autoparts.store/Store`
  snapshot -- the same append-only ledger, part-lot-shipment drafts and
  PPAP-certificate drafts the governor writes. Pure data transforms
  only: no I/O, no network, no signature. The manufacturer's own act
  is to sign and file the package; this namespace only materializes
  the package body.

  This is the honest delivery of the industry-stack `:export?` contract
  (robotics / audit-ledger capabilities) for ISIC 2930."
  (:require [clojure.string :as str]
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
