(ns autoparts.pedigree-integration-test
  "ADR-2607999950's critical end-to-end proof: a GENUINE cross-repo
  call into `cloud-itonami-isic-2410`'s OWN `steelworks.robotics`/
  `steelworks.export` -- never a hand-written EDN literal that merely
  mimics what those functions would produce. `real-upstream-pedigree`
  below builds a real steel-heat map and calls
  `steelworks.robotics/tensile-test-telemetry-for` (a real,
  `physics-2d`-time-stepped rigid-body simulation) and `steelworks.
  export/pedigree-for-heat` (both required from `cloud-itonami-
  isic-2410`'s own source, via this repo's `:cross-repo-test` alias --
  see deps.edn) to produce the pedigree this actor's governor then
  independently re-verifies.

  Run with `clojure -M:dev:cross-repo-test` -- kept OUT of the default
  `:test` alias (this file lives in `test-cross-repo/`, a separate
  source root) because it requires a same-org sibling checkout of
  `cloud-itonami-isic-2410` (`:local/root \"../cloud-itonami-isic-
  2410\"`, the SAME workspace-sibling convention this repo's own
  `io.github.kotoba-lang/langgraph`/`robotics` deps already use one
  org level up) that a casual fork of just THIS repo would not have.
  Still no live network call between actors at runtime: this is a
  build-time classpath dependency exercised by tests, same category
  as every other `:local/root` dependency in this fleet."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.pedigree :as pedigree]
            [langgraph.graph :as g]
            [steelworks.export :as steel-export]
            [steelworks.robotics :as steel-robotics]
            [autoparts.governor :as governor]
            [autoparts.store :as store]
            [autoparts.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :quality-engineer :phase 3})

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

(defn- real-upstream-pedigree
  "THE genuine cross-repo call: builds a real steel-heat record, runs
  `cloud-itonami-isic-2410`'s OWN real `physics-2d` tensile-test
  simulation for it (`steelworks.robotics/tensile-test-telemetry-
  for`), and packages the result via that repo's OWN `steelworks.
  export/pedigree-for-heat` -- never a hand-typed EDN literal."
  [heat-id coupon-mass-kg issued-at]
  (let [heat (merge {:id heat-id :coupon-mass-kg coupon-mass-kg}
                     (steel-robotics/tensile-test-telemetry-for {:coupon-mass-kg coupon-mass-kg}))]
    (steel-export/pedigree-for-heat heat issued-at)))

(deftest real-cross-repo-pedigree-is-shape-valid
  (testing "a pedigree built from a REAL cloud-itonami-isic-2410 simulation passes kotoba.pedigree/valid?"
    (let [p (real-upstream-pedigree "heat-strong" 5.0 "2026-07-15")]
      (is (some? p))
      (is (true? (pedigree/valid? p)))
      (is (= "cloud-itonami-isic-2410" (:pedigree/issuing-actor p)))
      (testing "the claim is the REAL simulated reading, not invented -- independently recomputing the same simulation yields the identical number"
        (is (= (pedigree/claim-value p :tensile-test-load-n)
               (:sim-tensile-load-n (steel-robotics/tensile-test-telemetry-for {:coupon-mass-kg 5.0}))))
        (is (= 8000.0 (pedigree/claim-value p :tensile-test-load-n))
            "documents the actual real-simulation value at coupon-mass-kg=5.0, for a human reader's sanity")))))

(deftest real-cross-repo-pedigree-genuinely-clears-autoparts-governor
  (testing "a heavy-enough real steel heat's pedigree genuinely clears autoparts.governor's independent acceptance check end-to-end, and a real part-lot ships"
    (let [pedigree (real-upstream-pedigree "heat-strong" 5.0 "2026-07-15")
          _ (is (>= (pedigree/claim-value pedigree :tensile-test-load-n) governor/min-upstream-tensile-load-n)
                "sanity: this heat's REAL simulated load actually clears autoparts' own disclosed floor")
          db (store/seed-db)
          actor (op/build db)]
      (verify! actor "e1pre" "lot-1")
      (simulate-robotics! actor "e1pre2" "lot-1")
      (exec-op actor "e1pre3" {:op :part-lot/intake :subject "lot-1"
                               :patch {:id "lot-1" :upstream-pedigree pedigree}} operator)
      (is (= pedigree (:upstream-pedigree (store/part-lot db "lot-1")))
          "the REAL cross-repo pedigree landed on the part-lot record unmodified")
      (let [res (exec-op actor "e1" {:op :actuation/ship-part-lot :subject "lot-1"} operator)]
        (is (= :interrupted (:status res))
            "governor's independent re-verification found no violation from the real pedigree -- escalates for human approval, same as any clean shipment")
        (let [r2 (approve! actor "e1")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:part-lot-shipped? (store/part-lot db "lot-1")))))))))

(deftest real-cross-repo-pedigree-genuinely-fails-autoparts-governor
  (testing "a too-light real steel heat's pedigree genuinely fails autoparts.governor's independent acceptance check end-to-end -- HARD hold, derived from a REAL cloud-itonami-isic-2410 simulation output, never a hand-crafted failing fixture"
    (let [pedigree (real-upstream-pedigree "heat-weak" 2.0 "2026-07-15")
          _ (is (< (pedigree/claim-value pedigree :tensile-test-load-n) governor/min-upstream-tensile-load-n)
                "sanity: this heat's REAL simulated load actually falls short of autoparts' own disclosed floor")
          db (store/seed-db)
          actor (op/build db)]
      (verify! actor "e2pre" "lot-1")
      (simulate-robotics! actor "e2pre2" "lot-1")
      (exec-op actor "e2pre3" {:op :part-lot/intake :subject "lot-1"
                               :patch {:id "lot-1" :upstream-pedigree pedigree}} operator)
      (let [res (exec-op actor "e2" {:op :actuation/ship-part-lot :subject "lot-1"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:upstream-pedigree-claims-out-of-tolerance} (-> (store/ledger db) last :basis)))
        (is (empty? (store/shipment-history db)))))))
