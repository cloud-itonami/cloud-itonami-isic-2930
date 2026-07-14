(ns autoparts.governor-contract-test
  "The governor contract as executable tests -- the auto-parts-
  manufacturer analog of `cloud-itonami-isic-6512`'s `casualty.
  governor-contract-test`. The single invariant under test:

    Auto-Parts Advisor never ships a part-lot action or issues a PPAP
    certificate the Auto-Parts Governor would reject, `:actuation/
    ship-part-lot`/`:actuation/issue-ppap-certificate` NEVER auto-
    commit at any phase, `:part-lot/intake` (no direct capital risk)
    MAY auto-commit when clean, and every decision (commit OR hold)
    leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [autoparts.store :as store]
            [autoparts.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :quality-engineer :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- verify!
  "Walks `subject` through verify -> approve, leaving a PPAP evidence
  verification on file. Uses distinct thread-ids per call site by
  suffixing `tid-prefix`."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-verify") {:op :ppap-evidence/verify :subject subject} operator)
  (approve! actor (str tid-prefix "-verify")))

(defn- screen!
  "Walks `subject` through process-capability screening -> approve,
  leaving a screening on file. Only safe to call for a part-lot whose
  defect status has already resolved -- an unresolved defect
  HARD-holds the screen itself (see
  `process-capability-defect-is-held-and-unoverridable`)."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-screen") {:op :process-capability/screen :subject subject} operator)
  (approve! actor (str tid-prefix "-screen")))

(defn- simulate-robotics!
  "Walks `subject` through the robot CMM/torque/weld-inspection
  verification mission -> approve, leaving `:robotics-sim-verified?`
  on file. Only meaningful to call for a part-lot whose critical-
  dimension-deviation is actually within tolerance -- an out-of-
  tolerance part-lot still gets :robotics-sim-verified? recorded (per
  whatever the mission itself found), but `autoparts.governor`'s
  independent recheck HARD-holds regardless (see
  `robotics-simulation-out-of-tolerance-is-held`)."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-robotics") {:op :robotics/simulate-inspection-cell :subject subject} operator)
  (approve! actor (str tid-prefix "-robotics")))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :part-lot/intake :subject "lot-1"
                   :patch {:id "lot-1" :part-lot-name "Meridian Brake Pad Lot BP-2044"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "Meridian Brake Pad Lot BP-2044" (:part-lot-name (store/part-lot db "lot-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest requirements-verify-always-needs-approval
  (testing "verify is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :ppap-evidence/verify :subject "lot-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/ppap-verification-of db "lot-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a ppap-evidence/verify proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :ppap-evidence/verify :subject "lot-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/ppap-verification-of db "lot-1")) "no verification written"))))

(deftest ship-part-lot-without-verification-is-held
  (testing "actuation/ship-part-lot before any PPAP evidence verification -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :actuation/ship-part-lot :subject "lot-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest part-lot-dppm-out-of-range-is-held
  (testing "a part-lot whose own DPPM reject rate falls outside its own quality-agreement bounds -> HOLD"
    (let [[db actor] (fresh)
          _ (verify! actor "t5pre" "lot-3")
          _ (simulate-robotics! actor "t5pre2" "lot-3")
          res (exec-op actor "t5" {:op :actuation/ship-part-lot :subject "lot-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:part-lot-dppm-out-of-range} (-> (store/ledger db) last :basis)))
      (is (empty? (store/shipment-history db))))))

(deftest process-capability-defect-is-held-and-unoverridable
  (testing "an unresolved process-capability defect on a part-lot -> HOLD, and never reaches request-approval -- exercised via :process-capability/screen DIRECTLY, not via the actuation op against an unscreened part-lot (see this actor's governor ns docstring / parksafety's ADR-2607071922 Decision 5 / automotive's ADR-0001 and every prior sibling's)"
    (let [[db actor] (fresh)
          res (exec-op actor "t6" {:op :process-capability/screen :subject "lot-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:process-capability-defect-unresolved} (-> (store/ledger db) first :basis)))
      (is (nil? (store/process-capability-screen-of db "lot-4")) "no clearance written"))))

(deftest ship-part-lot-always-escalates-then-human-decides
  (testing "a clean, fully-verified, in-spec part-lot still ALWAYS interrupts for human approval -- actuation/ship-part-lot is never auto"
    (let [[db actor] (fresh)
          _ (verify! actor "t7pre" "lot-1")
          _ (simulate-robotics! actor "t7pre2" "lot-1")
          r1 (exec-op actor "t7" {:op :actuation/ship-part-lot :subject "lot-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, shipment record drafted"
        (let [r2 (approve! actor "t7")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:part-lot-shipped? (store/part-lot db "lot-1"))))
          (is (= 1 (count (store/shipment-history db))) "one draft shipment record"))))))

(deftest issue-ppap-certificate-always-escalates-then-human-decides
  (testing "a clean, fully-verified, resolved-defect part-lot still ALWAYS interrupts for human approval -- actuation/issue-ppap-certificate is never auto"
    (let [[db actor] (fresh)
          _ (verify! actor "t8pre" "lot-1")
          _ (screen! actor "t8pre2" "lot-1")
          r1 (exec-op actor "t8" {:op :actuation/issue-ppap-certificate :subject "lot-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, certificate record drafted"
        (let [r2 (approve! actor "t8")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:ppap-certified? (store/part-lot db "lot-1"))))
          (is (= 1 (count (store/certificate-history db))) "one draft certificate record"))))))

(deftest ship-part-lot-double-shipment-is-held
  (testing "shipping the same part-lot's action twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (verify! actor "t9pre" "lot-1")
          _ (simulate-robotics! actor "t9pre2" "lot-1")
          _ (exec-op actor "t9a" {:op :actuation/ship-part-lot :subject "lot-1"} operator)
          _ (approve! actor "t9a")
          res (exec-op actor "t9" {:op :actuation/ship-part-lot :subject "lot-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-shipped} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/shipment-history db))) "still only the one earlier shipment"))))

(deftest issue-ppap-certificate-double-issuance-is-held
  (testing "issuing the same part-lot's PPAP certificate twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (verify! actor "t10pre" "lot-1")
          _ (screen! actor "t10pre2" "lot-1")
          _ (exec-op actor "t10a" {:op :actuation/issue-ppap-certificate :subject "lot-1"} operator)
          _ (approve! actor "t10a")
          res (exec-op actor "t10" {:op :actuation/issue-ppap-certificate :subject "lot-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-certified} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/certificate-history db))) "still only the one earlier certificate issuance"))))

(deftest robotics-simulation-always-needs-approval
  (testing "robotics/simulate-inspection-cell is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t11" {:op :robotics/simulate-inspection-cell :subject "lot-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t11")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (true? (:robotics-sim-verified? (store/part-lot db "lot-1"))))))))

(deftest ship-part-lot-without-robotics-simulation-is-held
  (testing "actuation/ship-part-lot before the robot CMM/torque/weld-inspection mission ever ran -> HOLD (robotics-simulation-missing)"
    (let [[db actor] (fresh)
          _ (verify! actor "t12pre" "lot-1")
          res (exec-op actor "t12" {:op :actuation/ship-part-lot :subject "lot-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:robotics-simulation-missing} (-> (store/ledger db) last :basis)))
      (is (empty? (store/shipment-history db))))))

(deftest robotics-simulation-out-of-tolerance-is-held
  (testing "lot-5 has a robotics-sim already on file, but its own critical-dimension-deviation reading falls outside its own tolerance bounds on INDEPENDENT recheck -> HOLD, never trusts the on-file verdict alone"
    (let [[db actor] (fresh)
          _ (verify! actor "t13pre" "lot-5")
          res (exec-op actor "t13" {:op :actuation/ship-part-lot :subject "lot-5"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:robotics-simulation-out-of-tolerance} (-> (store/ledger db) last :basis)))
      (is (empty? (store/shipment-history db))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :part-lot/intake :subject "lot-1"
                          :patch {:id "lot-1" :part-lot-name "Meridian Brake Pad Lot BP-2044"}} operator)
      (exec-op actor "b" {:op :ppap-evidence/verify :subject "lot-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
