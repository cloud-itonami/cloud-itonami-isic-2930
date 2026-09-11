(ns autoparts.upstream-pedigree-test
  "ADR-2607999950's cross-actor supply-chain-linkage check
  (`autoparts.governor/upstream-pedigree-claims-out-of-tolerance-
  violations`), exercised with HAND-BUILT `kotoba.pedigree` records
  (via the real `kotoba.pedigree/claim` constructor -- never a raw
  map literal that merely LOOKS like a pedigree). The genuine
  cross-repo proof -- an actual call into `cloud-itonami-isic-2410`'s
  `steelworks.export/pedigree-for-heat` -- lives in `test-cross-repo/
  autoparts/pedigree_integration_test.clj` (a separate alias, see
  deps.edn); this file only proves the GOVERNOR check itself is
  correct in isolation, independent of which upstream actor produced
  the pedigree."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.pedigree :as pedigree]
            [langgraph.graph :as g]
            [autoparts.governor :as governor]
            [autoparts.store :as store]
            [autoparts.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :quality-engineer :phase 3})

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- verify! [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-verify") {:op :ppap-evidence/verify :subject subject} operator)
  (approve! actor (str tid-prefix "-verify")))

(defn- simulate-robotics! [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-robotics") {:op :robotics/simulate-inspection-cell :subject subject} operator)
  (approve! actor (str tid-prefix "-robotics")))

(defn- attach-pedigree! [actor tid-prefix subject pedigree]
  (exec-op actor (str tid-prefix "-pedigree")
           {:op :part-lot/intake :subject subject
            :patch {:id subject :upstream-pedigree pedigree}}
           operator))

(defn- clean-pedigree []
  (pedigree/claim "PEDIGREE-heat-1" "heat-1" "cloud-itonami-isic-2410"
                   {:tensile-test-load-n (+ governor/min-upstream-tensile-load-n 100.0)}
                   :evidence-basis ["steelworks.robotics/run-tensile-test"]
                   :issued-at "2026-07-15"))

(defn- weak-pedigree []
  (pedigree/claim "PEDIGREE-heat-2" "heat-2" "cloud-itonami-isic-2410"
                   {:tensile-test-load-n (- governor/min-upstream-tensile-load-n 100.0)}
                   :evidence-basis ["steelworks.robotics/run-tensile-test"]
                   :issued-at "2026-07-15"))

(deftest absent-upstream-pedigree-is-a-no-op
  (testing "a part-lot with no :upstream-pedigree ships exactly as before this ADR -- no new violation"
    (let [[db actor] (fresh)
          _ (verify! actor "t1pre" "lot-1")
          _ (simulate-robotics! actor "t1pre2" "lot-1")
          res (exec-op actor "t1" {:op :actuation/ship-part-lot :subject "lot-1"} operator)]
      (is (nil? (:upstream-pedigree (store/part-lot db "lot-1"))))
      (is (= :interrupted (:status res)) "still escalates for human approval, same as before -- no HARD hold introduced")
      (let [r2 (approve! actor "t1")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (true? (:part-lot-shipped? (store/part-lot db "lot-1"))))))))

(deftest valid-in-tolerance-upstream-pedigree-ships-normally
  (testing "a shape-valid pedigree whose claim clears the acceptance floor does not block shipment"
    (let [[db actor] (fresh)
          _ (verify! actor "t2pre" "lot-1")
          _ (simulate-robotics! actor "t2pre2" "lot-1")
          _ (attach-pedigree! actor "t2pre3" "lot-1" (clean-pedigree))
          res (exec-op actor "t2" {:op :actuation/ship-part-lot :subject "lot-1"} operator)]
      (is (some? (:upstream-pedigree (store/part-lot db "lot-1"))))
      (is (= :interrupted (:status res)) "still escalates for human approval -- actuation is never auto")
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (true? (:part-lot-shipped? (store/part-lot db "lot-1"))))))))

(deftest upstream-pedigree-claims-out-of-tolerance-is-held
  (testing "a shape-valid pedigree whose claim falls below the acceptance floor -> HARD hold, independent of DPPM/robotics/PPAP being otherwise clean"
    (let [[db actor] (fresh)
          _ (verify! actor "t3pre" "lot-1")
          _ (simulate-robotics! actor "t3pre2" "lot-1")
          _ (attach-pedigree! actor "t3pre3" "lot-1" (weak-pedigree))
          res (exec-op actor "t3" {:op :actuation/ship-part-lot :subject "lot-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:upstream-pedigree-claims-out-of-tolerance} (-> (store/ledger db) last :basis)))
      (is (empty? (store/shipment-history db))))))

(deftest upstream-pedigree-invalid-shape-is-held
  (testing "an attached map that fails kotoba.pedigree/valid? (e.g. a non-numeric claim, mimicking a self-reported string) -> HARD hold, never trusted at face value"
    (let [[db actor] (fresh)
          bad-pedigree (assoc (clean-pedigree) :pedigree/claims {:tensile-test-load-n "plenty"})
          _ (verify! actor "t4pre" "lot-1")
          _ (simulate-robotics! actor "t4pre2" "lot-1")
          _ (attach-pedigree! actor "t4pre3" "lot-1" bad-pedigree)
          res (exec-op actor "t4" {:op :actuation/ship-part-lot :subject "lot-1"} operator)]
      (is (false? (pedigree/valid? bad-pedigree)) "sanity: the fixture really is shape-invalid")
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:upstream-pedigree-invalid-shape} (-> (store/ledger db) last :basis)))
      (is (empty? (store/shipment-history db))))))

(deftest upstream-pedigree-check-scoped-to-ship-part-lot-op
  (testing "the check only fires for :actuation/ship-part-lot -- an out-of-tolerance pedigree already on file does not block an unrelated op"
    (let [[_db actor] (fresh)
          _ (attach-pedigree! actor "t5pre" "lot-1" (weak-pedigree))
          res (exec-op actor "t5" {:op :ppap-evidence/verify :subject "lot-1"} operator)]
      (is (= :interrupted (:status res)) "ppap-evidence/verify is unaffected by an out-of-tolerance upstream pedigree")
      (let [r2 (approve! actor "t5")]
        (is (= :commit (get-in r2 [:state :disposition])))))))
