(ns autoparts.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:actuation/ship-part-lot`/`:actuation/issue-ppap-
  certificate` must NEVER be a member of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [autoparts.phase :as phase]))

(deftest ship-part-lot-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real robot part-lot shipment"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :actuation/ship-part-lot))
          (str "phase " n " must not auto-commit :actuation/ship-part-lot")))))

(deftest issue-ppap-certificate-never-auto-at-any-phase
  (testing "structural invariant: no phase auto-commits a real PPAP certificate"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :actuation/issue-ppap-certificate))
          (str "phase " n " must not auto-commit :actuation/issue-ppap-certificate")))))

(deftest process-capability-screen-never-auto-at-any-phase
  (testing "screening carries no direct capital risk, but is still never auto-eligible, matching every sibling screening op in this fleet"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :process-capability/screen))
          (str "phase " n " must not auto-commit :process-capability/screen")))))

(deftest robotics-simulate-inspection-cell-never-auto-at-any-phase
  (testing "the robot CMM/torque/weld-inspection verification mission carries no direct capital risk, but is still never auto-eligible, matching every sibling verification op in this fleet"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :robotics/simulate-inspection-cell))
          (str "phase " n " must not auto-commit :robotics/simulate-inspection-cell")))))

(deftest robotics-simulate-inspection-cell-enabled-from-phase-2
  (is (contains? (:writes (get phase/phases 2)) :robotics/simulate-inspection-cell))
  (is (contains? (:writes (get phase/phases 3)) :robotics/simulate-inspection-cell))
  (is (not (contains? (:writes (get phase/phases 1)) :robotics/simulate-inspection-cell))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-capital-risk-ops
  (testing ":part-lot/intake carries no direct capital risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:part-lot/intake} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :part-lot/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :actuation/ship-part-lot} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :actuation/issue-ppap-certificate} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :part-lot/intake} :commit)))))
