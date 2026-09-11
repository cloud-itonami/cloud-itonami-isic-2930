(ns autoparts.motionplan-test
  "autoparts.motionplan/motion-plan-for -- the Cartesian waypoint list
  built from autoparts.robotics/mission-actions's real 3-step sequence
  (ADR-2607160000)."
  (:require [clojure.test :refer [deftest is testing]]
            [autoparts.cad :as cad]
            [autoparts.motionplan :as motionplan]
            [autoparts.robotics :as robotics]))

(deftest one-waypoint-per-mission-action-same-order
  (let [plan (motionplan/motion-plan-for {:joint-mass-kg 2.5})]
    (is (= (count robotics/mission-actions) (count plan)))
    (is (= (mapv :step robotics/mission-actions) (mapv :step plan)))
    (is (= [1 2 3] (mapv :seq plan)))
    (is (= ["cmm-dimensional-scan" "fastener-torque-check" "weld-joint-ultrasonic-scan"]
           (mapv :station plan)))))

(deftest waypoints-are-spaced-along-the-travel-axis
  (let [plan (motionplan/motion-plan-for {:joint-mass-kg 2.5})
        xs (mapv #(first (:waypoint %)) plan)]
    (is (= [0.0 motionplan/station-pitch-m (* 2 motionplan/station-pitch-m)] xs))
    (is (every? #(= motionplan/default-tool-orientation (:tool-orientation %)) plan))
    (is (every? #(zero? (second (:waypoint %))) plan) "y is the line centerline")))

(deftest working-height-derives-from-the-part-lots-real-envelope
  (testing "z (working height) is half the part-lot's own real envelope height"
    (let [part-lot {:joint-mass-kg 2.5 :specimen-height-mm 10.0}
          plan (motionplan/motion-plan-for part-lot)
          z (nth (:waypoint (first plan)) 2)]
      (is (= (/ 10.0 2000.0) z))))
  (testing "a part-lot with no real :specimen-height-mm still gets a real
            answer via autoparts.cad's own disclosed default, not
            motionplan's separate fallback"
    (let [plan (motionplan/motion-plan-for {:joint-mass-kg 2.5})
          z (nth (:waypoint (first plan)) 2)]
      (is (= (/ cad/default-specimen-height-mm 2000.0) z))))
  (testing "no part-lot at all (older/hand-rolled caller) -> motionplan's own default-working-height-m"
    (let [plan (motionplan/motion-plan-for)
          z (nth (:waypoint (first plan)) 2)]
      (is (= motionplan/default-working-height-m z)))))

(deftest deterministic-same-part-lot-same-plan
  (is (= (motionplan/motion-plan-for {:joint-mass-kg 2.5 :specimen-height-mm 4.0})
         (motionplan/motion-plan-for {:joint-mass-kg 2.5 :specimen-height-mm 4.0}))))
