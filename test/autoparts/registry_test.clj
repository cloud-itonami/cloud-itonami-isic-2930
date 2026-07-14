(ns autoparts.registry-test
  (:require [clojure.test :refer [deftest is]]
            [autoparts.registry :as r]))

;; ----------------------------- part-lot-dppm-out-of-range? -----------------------------

(deftest not-out-of-range-when-within-bounds
  (is (not (r/part-lot-dppm-out-of-range? {:dppm-actual 45 :dppm-min 0 :dppm-max 300})))
  (is (not (r/part-lot-dppm-out-of-range? {:dppm-actual 0 :dppm-min 0 :dppm-max 300})))
  (is (not (r/part-lot-dppm-out-of-range? {:dppm-actual 300 :dppm-min 0 :dppm-max 300}))))

(deftest out-of-range-when-below-minimum-or-above-maximum
  (is (r/part-lot-dppm-out-of-range? {:dppm-actual -5 :dppm-min 0 :dppm-max 300}))
  (is (r/part-lot-dppm-out-of-range? {:dppm-actual 850 :dppm-min 0 :dppm-max 300})))

(deftest out-of-range-is-false-on-missing-fields
  (is (not (r/part-lot-dppm-out-of-range? {})))
  (is (not (r/part-lot-dppm-out-of-range? {:dppm-actual 850}))))

;; ----------------------------- register-part-lot-shipment -----------------------------

(deftest shipment-is-a-draft-not-a-real-shipment
  (let [result (r/register-part-lot-shipment "lot-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest shipment-assigns-shipment-number
  (let [result (r/register-part-lot-shipment "lot-1" "JPN" 7)]
    (is (= (get result "shipment_number") "JPN-SHP-000007"))
    (is (= (get-in result ["record" "part_lot_id"]) "lot-1"))
    (is (= (get-in result ["record" "kind"]) "part-lot-shipment-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest shipment-validation-rules
  (is (thrown? Exception (r/register-part-lot-shipment "" "JPN" 0)))
  (is (thrown? Exception (r/register-part-lot-shipment "lot-1" "" 0)))
  (is (thrown? Exception (r/register-part-lot-shipment "lot-1" "JPN" -1))))

;; ----------------------------- register-ppap-certificate -----------------------------

(deftest certificate-is-a-draft-not-real-certification
  (let [result (r/register-ppap-certificate "lot-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest certificate-assigns-certificate-number
  (let [result (r/register-ppap-certificate "lot-1" "JPN" 3)]
    (is (= (get result "certificate_number") "JPN-PPAP-000003"))
    (is (= (get-in result ["record" "part_lot_id"]) "lot-1"))
    (is (= (get-in result ["record" "kind"]) "ppap-certificate-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest certificate-validation-rules
  (is (thrown? Exception (r/register-ppap-certificate "" "JPN" 0)))
  (is (thrown? Exception (r/register-ppap-certificate "lot-1" "" 0)))
  (is (thrown? Exception (r/register-ppap-certificate "lot-1" "JPN" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-part-lot-shipment "lot-1" "JPN" 0)
        hist (r/append [] c1)
        c2 (r/register-part-lot-shipment "lot-2" "JPN" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-SHP-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-SHP-000001" (get-in hist2 [1 "record_id"])))))
