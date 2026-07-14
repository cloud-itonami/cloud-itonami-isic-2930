(ns autoparts.export-test
  "Audit-package export contract -- social/regulatory hand-off shape."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [langgraph.graph :as g]
            [autoparts.export :as export]
            [autoparts.operation :as op]
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
