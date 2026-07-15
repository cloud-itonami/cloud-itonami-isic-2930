(ns autoparts.export-test
  "Audit-package export contract -- social/regulatory hand-off shape,
  plus `pedigree-for-part-lot`'s cross-actor supply-chain-linkage
  export (ADR-2607999960)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.pedigree :as pedigree]
            [langgraph.graph :as g]
            [autoparts.export :as export]
            [autoparts.operation :as op]
            [autoparts.robotics :as robotics]
            [autoparts.store :as store]))

(def operator {:actor-id "op-1" :actor-role :quality-engineer :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn- seed-with-one-shipment []
  (let [db (store/seed-db)
        actor (op/build db)]
    (exec! actor "v" {:op :ppap-evidence/verify :subject "lot-1"})
    (approve! actor "v")
    (exec! actor "r" {:op :robotics/simulate-inspection-cell :subject "lot-1"})
    (approve! actor "r")
    (exec! actor "d" {:op :actuation/ship-part-lot :subject "lot-1"})
    (approve! actor "d")
    db))

(deftest audit-package-shape
  (let [db (seed-with-one-shipment)
        pkg (export/audit-package db)]
    (is (= "2930" (:isic pkg)))
    (is (= "cloud-itonami-isic-2930" (:business-id pkg)))
    (is (= :edn-maps (:format pkg)))
    (is (pos? (get-in pkg [:counts :ledger])))
    (is (= 1 (get-in pkg [:counts :shipments])))
    (is (some #(= "lot-1" (:id %)) (:part-lots pkg)))
    (is (true? (:part-lot-shipped?
                (first (filter #(= "lot-1" (:id %)) (:part-lots pkg))))))))

(deftest csv-bundle-has-headers-and-rows
  (let [db (seed-with-one-shipment)
        bundle (export/package->csv-bundle db)]
    (is (every? bundle ["part-lots.csv" "ledger.csv" "shipments.csv" "ppap-certificates.csv"]))
    (is (str/starts-with? (get bundle "part-lots.csv") "id,part-lot-name,"))
    (is (re-find #"lot-1" (get bundle "part-lots.csv")))
    (is (re-find #"JPN-SHP-000000" (get bundle "shipments.csv")))
    (is (re-find #":actuation/ship-part-lot" (get bundle "ledger.csv")))))

(deftest empty-store-export-is-usable
  (let [db (store/seed-db)
        pkg (export/audit-package db)
        bundle (export/package->csv-bundle db)]
    (is (= 0 (get-in pkg [:counts :shipments])))
    (is (= 5 (get-in pkg [:counts :part-lots])))
    (is (str/includes? (get bundle "ledger.csv") "seq,t,op"))))

;; ---------------------------------------------------------------------------
;; pedigree-for-part-lot (ADR-2607999960 cross-actor supply-chain linkage)
;; ---------------------------------------------------------------------------

(deftest pedigree-for-part-lot-builds-a-valid-pedigree-from-real-telemetry
  (testing "a part-lot carrying its own real, already-simulated proof-load telemetry yields a shape-valid pedigree"
    (let [part-lot (merge {:id "lot-pedigree-1" :joint-mass-kg 2.5}
                           (robotics/pull-test-telemetry-for {:joint-mass-kg 2.5}))
          p (export/pedigree-for-part-lot part-lot "2026-07-15")]
      (is (some? p))
      (is (true? (pedigree/valid? p)))
      (is (= "lot-pedigree-1" (:pedigree/subject-lot-id p)))
      (is (= "cloud-itonami-isic-2930" (:pedigree/issuing-actor p)))
      (is (= "2026-07-15" (:pedigree/issued-at p)))
      (is (not (contains? p :pedigree/upstream))
          "no :upstream-pedigree on file -> no :pedigree/upstream key at all")
      (testing "the claim value is the part-lot's OWN real simulated reading, not invented"
        (is (= (:sim-proof-load-force part-lot)
               (pedigree/claim-value p :proof-load-force-n)))
        (is (= (:sim-proof-load-force (robotics/pull-test-telemetry-for {:joint-mass-kg 2.5}))
               (pedigree/claim-value p :proof-load-force-n))))))
  (testing "a heavier joint-mass-kg yields a proportionally larger pedigree claim -- proves the claim tracks the real simulated trajectory, not a fixed number"
    (let [light-lot (merge {:id "lot-light"} (robotics/pull-test-telemetry-for {:joint-mass-kg 1.0}))
          heavy-lot (merge {:id "lot-heavy"} (robotics/pull-test-telemetry-for {:joint-mass-kg 3.0}))
          light-p (export/pedigree-for-part-lot light-lot "2026-07-15")
          heavy-p (export/pedigree-for-part-lot heavy-lot "2026-07-15")]
      (is (< (pedigree/claim-value light-p :proof-load-force-n)
             (pedigree/claim-value heavy-p :proof-load-force-n))))))

(deftest pedigree-for-part-lot-never-fabricates-missing-telemetry
  (testing "a part-lot with no real :sim-proof-load-force on file yields nil, never an invented pedigree"
    (is (nil? (export/pedigree-for-part-lot {:id "lot-x"} "2026-07-15")))
    (is (nil? (export/pedigree-for-part-lot {:id "lot-x" :joint-mass-kg 2.5} "2026-07-15"))
        "joint-mass-kg alone is not telemetry -- the simulation must actually have been run and merged in first")))

(deftest pedigree-for-part-lot-embeds-a-genuine-upstream-pedigree
  (testing "a part-lot carrying :upstream-pedigree (an isic-2410 steel-heat pedigree already independently re-verified by this actor's own governor) embeds it verbatim as :pedigree/upstream -- a real 2-hop chain"
    (let [steel-pedigree (pedigree/claim "PEDIGREE-heat-1" "heat-1" "cloud-itonami-isic-2410"
                                          {:tensile-test-load-n 8000.0}
                                          :evidence-basis ["steelworks.robotics/run-tensile-test"]
                                          :issued-at "2026-07-15")
          part-lot (merge {:id "lot-pedigree-2" :joint-mass-kg 2.5 :upstream-pedigree steel-pedigree}
                           (robotics/pull-test-telemetry-for {:joint-mass-kg 2.5}))
          p (export/pedigree-for-part-lot part-lot "2026-07-15")]
      (is (true? (pedigree/valid? p)))
      (is (= steel-pedigree (:pedigree/upstream p)))
      (testing "each hop's own claim stays independently readable -- a genuine steel -> part chain, not a flattened summary"
        (is (= 8000.0 (pedigree/claim-value (:pedigree/upstream p) :tensile-test-load-n)))
        (is (= (:sim-proof-load-force part-lot) (pedigree/claim-value p :proof-load-force-n)))))))
