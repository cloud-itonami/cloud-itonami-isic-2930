(ns autoparts.cad-test
  "autoparts.cad's real BREP packaging-envelope bridge (ADR-2607160000)
  -- envelope-dims-mm's real-vs-default fallback discipline, and
  envelope-solid/envelope-mesh's genuine tessellation output."
  (:require [clojure.test :refer [deftest is testing]]
            [autoparts.cad :as cad]
            [autoparts.robotics :as robotics]))

(deftest envelope-dims-mm-falls-back-to-disclosed-defaults-when-absent
  (testing "a part-lot with no :specimen-*-mm fields gets the disclosed defaults"
    (is (= {:length-mm cad/default-specimen-length-mm
            :width-mm cad/default-specimen-width-mm
            :height-mm cad/default-specimen-height-mm}
           (cad/envelope-dims-mm {:id "lot-x" :joint-mass-kg 2.5}))))
  (testing "nil part-lot also falls back cleanly"
    (is (= {:length-mm cad/default-specimen-length-mm
            :width-mm cad/default-specimen-width-mm
            :height-mm cad/default-specimen-height-mm}
           (cad/envelope-dims-mm nil)))))

(deftest envelope-dims-mm-uses-a-part-lots-own-real-measurement-when-present
  (testing "an explicit :specimen-*-mm triple overrides the defaults"
    (is (= {:length-mm 35.0 :width-mm 90.0 :height-mm 2.0}
           (cad/envelope-dims-mm {:specimen-length-mm 35.0
                                   :specimen-width-mm 90.0
                                   :specimen-height-mm 2.0}))))
  (testing "a partial triple only overrides the fields actually given"
    (is (= {:length-mm 40.0
            :width-mm cad/default-specimen-width-mm
            :height-mm cad/default-specimen-height-mm}
           (cad/envelope-dims-mm {:specimen-length-mm 40.0})))))

(deftest default-specimen-dims-reproduce-robotics-prior-fixed-constants
  (testing "the disclosed fallback defaults are DEFINED to exactly reproduce
            autoparts.robotics's pre-ADR-2607160000 jaw-half-w-m/jaw-half-h-m
            figures, so a part-lot with nothing on file behaves identically
            to this actor's behavior before this ADR"
    (is (= robotics/jaw-half-w-m (/ cad/default-specimen-length-mm 2000.0)))
    (is (= robotics/jaw-half-h-m (/ cad/default-specimen-width-mm 2000.0)))))

(deftest envelope-solid-produces-real-tessellatable-geometry
  (let [{:keys [dims] :as solid} (cad/envelope-solid {:specimen-length-mm 35.0
                                                        :specimen-width-mm 90.0
                                                        :specimen-height-mm 2.0})]
    (is (= {:length-mm 35.0 :width-mm 90.0 :height-mm 2.0} dims))
    (is (seq (:vertices solid)))
    (is (seq (:edges solid)))
    (testing "the tessellated footprint's X/Y extent matches the requested dims (mm)"
      (let [{:keys [positions]} (cad/envelope-mesh solid)
            extent (fn [axis] (- (apply max (map #(nth % axis) positions))
                                  (apply min (map #(nth % axis) positions))))]
        (is (< (Math/abs (- (extent 0) 35.0)) 1e-6))
        (is (< (Math/abs (- (extent 1) 90.0)) 1e-6))))))

(deftest envelope-mesh-is-well-formed
  (let [solid (cad/envelope-solid {:specimen-length-mm 20.0
                                    :specimen-width-mm 100.0
                                    :specimen-height-mm 3.0})
        {:keys [positions indices]} (cad/envelope-mesh solid)]
    (is (pos? (count positions)))
    (is (pos? (count indices)))
    (is (zero? (mod (count indices) 3)) "indices are complete triangles")
    (is (every? #(<= 0 % (dec (count positions))) indices)
        "every index references a valid vertex")
    (is (every? #(= 3 (count %)) positions) "positions are [x y z]")))

(deftest envelope-dims-mm-vary-per-part-lot
  (testing "two part-lots with different real coupon measurements get
            genuinely different envelopes -- this is not a fixed constant
            dressed up as per-lot data"
    (is (not= (cad/envelope-dims-mm {:specimen-length-mm 25.0 :specimen-width-mm 45.0})
              (cad/envelope-dims-mm {:specimen-length-mm 60.0 :specimen-width-mm 120.0})))))
