(ns autoparts.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a configuration
  change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the sibling
  actor."
  (:require [clojure.test :refer [deftest is testing]]
            [autoparts.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Meridian Brake Pad Lot BP-2044" (:part-lot-name (store/part-lot s "lot-1"))))
      (is (= "JPN" (:jurisdiction (store/part-lot s "lot-1"))))
      (is (= 45 (:dppm-actual (store/part-lot s "lot-1"))))
      (is (= 0 (:dppm-min (store/part-lot s "lot-1"))))
      (is (= 300 (:dppm-max (store/part-lot s "lot-1"))))
      (is (false? (:process-capability-defect-unresolved? (store/part-lot s "lot-1"))))
      (is (= 850 (:dppm-actual (store/part-lot s "lot-3"))))
      (is (true? (:process-capability-defect-unresolved? (store/part-lot s "lot-4"))))
      (is (false? (:robotics-sim-verified? (store/part-lot s "lot-1"))) "no robotics mission has run yet")
      (is (true? (:robotics-sim-verified? (store/part-lot s "lot-5"))) "seeded as already-on-file")
      (is (= 0.12 (:critical-dimension-deviation-actual (store/part-lot s "lot-5"))))
      (is (false? (:part-lot-shipped? (store/part-lot s "lot-1"))))
      (is (false? (:ppap-certified? (store/part-lot s "lot-1"))))
      (is (= ["lot-1" "lot-2" "lot-3" "lot-4" "lot-5"]
             (mapv :id (store/all-part-lots s))))
      (is (nil? (store/process-capability-screen-of s "lot-1")))
      (is (nil? (store/ppap-verification-of s "lot-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/shipment-history s)))
      (is (= [] (store/certificate-history s)))
      (is (zero? (store/next-shipment-sequence s "JPN")))
      (is (zero? (store/next-certificate-sequence s "JPN")))
      (is (false? (store/part-lot-already-shipped? s "lot-1")))
      (is (false? (store/part-lot-already-certified? s "lot-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :part-lot/upsert
                                 :value {:id "lot-1" :part-lot-name "Meridian Brake Pad Lot BP-2044"}})
        (is (= "Meridian Brake Pad Lot BP-2044" (:part-lot-name (store/part-lot s "lot-1"))))
        (is (= 45 (:dppm-actual (store/part-lot s "lot-1"))) "unrelated field preserved"))
      (testing "robotics-sim result commits via :part-lot/upsert and reads back"
        (store/commit-record! s {:effect :part-lot/upsert
                                 :value {:id "lot-1" :robotics-sim-verified? true
                                        :robotics-sim-record {:mission-id "m-1" :passed? true}}})
        (is (true? (:robotics-sim-verified? (store/part-lot s "lot-1"))))
        (is (= {:mission-id "m-1" :passed? true} (:robotics-sim-record (store/part-lot s "lot-1"))))
        (is (= 45 (:dppm-actual (store/part-lot s "lot-1"))) "unrelated field still preserved"))
      (testing "verification / process-capability-screen payloads commit and read back"
        (store/commit-record! s {:effect :ppap-verification/set :path ["lot-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/ppap-verification-of s "lot-1")))
        (store/commit-record! s {:effect :process-capability-screen/set :path ["lot-1"]
                                 :payload {:part-lot-id "lot-1" :verdict :resolved}})
        (is (= {:part-lot-id "lot-1" :verdict :resolved} (store/process-capability-screen-of s "lot-1"))))
      (testing "part-lot shipment drafts a record and advances the sequence"
        (store/commit-record! s {:effect :part-lot/mark-shipped :path ["lot-1"]})
        (is (= "JPN-SHP-000000" (get (first (store/shipment-history s)) "record_id")))
        (is (= "part-lot-shipment-draft" (get (first (store/shipment-history s)) "kind")))
        (is (true? (:part-lot-shipped? (store/part-lot s "lot-1"))))
        (is (= 1 (count (store/shipment-history s))))
        (is (= 1 (store/next-shipment-sequence s "JPN")))
        (is (true? (store/part-lot-already-shipped? s "lot-1")))
        (is (false? (store/part-lot-already-shipped? s "lot-2"))))
      (testing "PPAP certificate drafts a record and advances the sequence"
        (store/commit-record! s {:effect :part-lot/mark-certified :path ["lot-1"]})
        (is (= "JPN-PPAP-000000" (get (first (store/certificate-history s)) "record_id")))
        (is (= "ppap-certificate-draft" (get (first (store/certificate-history s)) "kind")))
        (is (true? (:ppap-certified? (store/part-lot s "lot-1"))))
        (is (= 1 (count (store/certificate-history s))))
        (is (= 1 (store/next-certificate-sequence s "JPN")))
        (is (true? (store/part-lot-already-certified? s "lot-1")))
        (is (false? (store/part-lot-already-certified? s "lot-2"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/part-lot s "nope")))
    (is (= [] (store/all-part-lots s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/shipment-history s)))
    (is (= [] (store/certificate-history s)))
    (is (zero? (store/next-shipment-sequence s "JPN")))
    (is (zero? (store/next-certificate-sequence s "JPN")))
    (store/with-part-lots s {"x" {:id "x" :part-lot-name "n" :dppm-actual 45
                                   :dppm-min 0 :dppm-max 300
                                   :process-capability-defect-unresolved? false
                                   :part-lot-shipped? false :ppap-certified? false
                                   :jurisdiction "JPN" :status :intake}})
    (is (= "n" (:part-lot-name (store/part-lot s "x"))))))
