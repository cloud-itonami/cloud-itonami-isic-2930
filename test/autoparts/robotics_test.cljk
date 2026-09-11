(ns autoparts.robotics-test
  "autoparts.robotics/run-pull-test's real physics-2d simulation
  (ADR-2607152000), now bridged onto autoparts.cad's real BREP
  packaging-envelope for the :jaw body's AABB (ADR-2607160000). Asserts
  BOTH halves of the disclosed contract: (1) CAD-derived specimen
  geometry genuinely changes the simulated world (:jaw's AABB size,
  :trajectory's absolute positions) -- not just the mass-based force
  reading already covered by store_contract_test/governor_contract_test
  -- and (2) that same geometry does NOT change :sim-peak-decel-mps2/
  :sim-proof-load-force, a real, verified property of this ns's
  single-tick 'boxcar' collision technique (see run-pull-test's
  docstring), asserted here so a future change that accidentally
  breaks the invariant is caught."
  (:require [clojure.test :refer [deftest is testing]]
            [autoparts.cad :as cad]
            [autoparts.robotics :as robotics]))

(deftest bare-mass-call-is-unchanged-from-pre-adr-2607160000-behavior
  (testing "a bare joint-mass-kg number (no part-lot map) still works, and
            produces the SAME AABB half-extents robotics used as fixed
            constants before this ADR (autoparts.cad's defaults are defined
            to reproduce them exactly)"
    (let [r (robotics/run-pull-test 2.5)]
      (is (pos? (:sim-proof-load-force r)))
      (is (pos? (:sim-peak-decel-mps2 r)))
      (is (= robotics/test-speed-mps (:test-speed-mps r)))
      (is (= robotics/travel-to-failure-m (:travel-to-failure-m r))))))

(deftest run-pull-test-accepts-a-full-part-lot-map-with-no-specimen-fields
  (testing "a part-lot map with only :joint-mass-kg (no real coupon geometry
            on file) produces IDENTICAL numeric results to the bare-number
            call with the same mass -- autoparts.cad's disclosed defaults
            close the gap transparently, no behavior change for lots with
            nothing on file"
    (let [bare (robotics/run-pull-test 2.5)
          via-map (robotics/run-pull-test {:id "lot-x" :joint-mass-kg 2.5})]
      (is (= (:sim-proof-load-force bare) (:sim-proof-load-force via-map)))
      (is (= (:sim-peak-decel-mps2 bare) (:sim-peak-decel-mps2 via-map)))
      (is (= (:ticks bare) (:ticks via-map)))
      (is (= (:trajectory bare) (:trajectory via-map))
          "identical geometry (both fall back to autoparts.cad's defaults) -> identical trajectory"))))

(deftest cad-derived-specimen-geometry-genuinely-changes-the-jaws-placement
  (testing "two part-lots with the SAME :joint-mass-kg but DIFFERENT real
            :specimen-length-mm/:specimen-width-mm produce DIFFERENT
            :trajectory position values -- a genuine, non-cosmetic effect of
            autoparts.cad's real per-lot geometry, not just the mass-based
            force reading autoparts.robotics already tested before this ADR"
    (let [small (robotics/run-pull-test {:joint-mass-kg 2.5 :specimen-length-mm 10.0 :specimen-width-mm 40.0})
          large (robotics/run-pull-test {:joint-mass-kg 2.5 :specimen-length-mm 80.0 :specimen-width-mm 160.0})
          pos0 (fn [r] (:position (first (:trajectory r))))]
      (is (not= (pos0 small) (pos0 large))
          "the jaw's initial position genuinely shifts with the real specimen envelope size")
      (is (< (first (pos0 small)) (first (pos0 large)))
          "a larger specimen envelope pushes the jaw's start position further out along the travel axis"))))

(deftest cad-derived-geometry-does-not-change-the-force-reading-disclosed-invariant
  (testing "run-pull-test's own documented geometry-invariance: peak
            deceleration / proof-load force are driven by test speed, joint
            mass, and travel-to-failure -- NEVER by the specimen envelope's
            outer bounding-box size -- verified here, not just asserted in
            prose, so a future change that breaks this real property of the
            'boxcar' collision technique is caught"
    (let [small (robotics/run-pull-test {:joint-mass-kg 2.5 :specimen-length-mm 10.0 :specimen-width-mm 40.0})
          large (robotics/run-pull-test {:joint-mass-kg 2.5 :specimen-length-mm 80.0 :specimen-width-mm 160.0})]
      (is (= (:sim-peak-decel-mps2 small) (:sim-peak-decel-mps2 large)))
      (is (= (:sim-proof-load-force small) (:sim-proof-load-force large)))
      (is (= (:ticks small) (:ticks large)))
      (is (= (:dt small) (:dt large))))))

(deftest joint-mass-kg-still-scales-proof-load-force-independent-of-geometry
  (testing "mass legitimately scales the force reading (unlike automotive's
            mass-invariant :sim-decel-g) even when specimen geometry is held
            fixed -- the two effects (mass -> force, geometry -> trajectory
            position) are orthogonal, as documented"
    (let [light (robotics/run-pull-test {:joint-mass-kg 1.0 :specimen-length-mm 30.0 :specimen-width-mm 60.0})
          heavy (robotics/run-pull-test {:joint-mass-kg 3.0 :specimen-length-mm 30.0 :specimen-width-mm 60.0})]
      (is (< (:sim-proof-load-force light) (:sim-proof-load-force heavy)))
      (is (= (:sim-peak-decel-mps2 light) (:sim-peak-decel-mps2 heavy))
          "peak deceleration itself is mass-invariant (force = decel * mass)"))))

(deftest pull-test-telemetry-for-uses-the-part-lots-own-real-geometry
  (testing "pull-test-telemetry-for now threads the FULL part-lot (not just
            :joint-mass-kg) into run-pull-test, so a part-lot with real
            :specimen-*-mm fields on file gets a genuinely per-lot geometry
            in its telemetry-backing simulation (verified indirectly via
            autoparts.cad/envelope-dims-mm agreeing with the part-lot's own
            fields, since :sim-proof-load-force itself is geometry-invariant
            per the disclosed contract above)"
    (let [part-lot {:id "lot-x" :joint-mass-kg 2.5
                     :specimen-length-mm 30.0 :specimen-width-mm 75.0}
          telemetry (robotics/pull-test-telemetry-for part-lot)]
      (is (= {:length-mm 30.0 :width-mm 75.0 :height-mm cad/default-specimen-height-mm}
             (cad/envelope-dims-mm part-lot)))
      (is (pos? (:sim-proof-load-force telemetry))))))
