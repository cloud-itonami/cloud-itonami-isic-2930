(ns autoparts.scene-test
  "autoparts.scene's bridge from autoparts.cad's tessellated envelope +
  autoparts.robotics/run-pull-test's trajectory into kami.webgpu.mesh's
  real input shape, asserted for well-formedness -- no browser/WebGPU
  device is available in this JVM/.cljc actor repo (see autoparts.scene's
  docstring). Direct port of vdesign.scene's own scene_test.cljc
  assertions (ADR-2607151600), adapted to a plain part-lot map instead
  of running a langgraph design actor."
  (:require [clojure.test :refer [deftest is testing]]
            [autoparts.robotics :as robotics]
            [autoparts.scene :as scene]))

(def ^:private sample-part-lot
  {:id "lot-scene-test" :joint-mass-kg 2.5
   :specimen-length-mm 35.0 :specimen-width-mm 90.0 :specimen-height-mm 2.5})

(deftest mesh-data-is-well-formed
  (testing "positions/normals/indices satisfy kami.webgpu.mesh/upload-mesh!'s
            real contract: same-length positions/normals, index count a
            multiple of 3, every index within the vertex range"
    (let [{:keys [positions normals indices vertex-count index-count]} (scene/scene-for sample-part-lot)]
      (is (pos? vertex-count))
      (is (pos? index-count))
      (is (= (count positions) vertex-count))
      (is (= (count normals) vertex-count)
          "upload-mesh! requires one normal per vertex, not optional like uvs/skin/morph")
      (is (= (count indices) index-count))
      (is (zero? (mod index-count 3)))
      (is (every? #(<= 0 % (dec vertex-count)) indices)
          "every index must reference a valid vertex")
      (is (every? #(= 3 (count %)) positions) "positions are [x y z]")
      (is (every? #(= 3 (count %)) normals) "normals are [x y z]")
      (is (every? (fn [n] (< (Math/abs (- 1.0 (Math/sqrt (reduce + (map * n n))))) 1e-6)) normals)
          "every normal must actually be unit-length"))))

(deftest one-frame-per-simulated-tick
  (testing "one :transform per autoparts.robotics/run-pull-test trajectory tick"
    (let [sim (robotics/run-pull-test sample-part-lot)
          sc (scene/scene-for sample-part-lot)]
      (is (= (:ticks sim) (count (:frames sc))))
      (is (every? #(= 3 (count (get-in % [:transform :translation]))) (:frames sc)))
      (is (every? #(= [0.0 0.0 0.0] (get-in % [:transform :rotation])) (:frames sc))
          "physics-2d has no orientation state -- every frame's rotation is identity, honestly")
      (is (every? #(= [1.0 1.0 1.0] (get-in % [:transform :scale])) (:frames sc)))
      ;; translations move: the scene isn't rendering a frozen frame.
      (is (not= (get-in (first (:frames sc)) [:transform :translation])
                (get-in (last (:frames sc)) [:transform :translation]))))))

(deftest mesh-is-unit-converted-to-meters-and-already-centered-in-xy
  (testing "the mesh's XY footprint extent (now in METERS, matching
            autoparts.robotics's trajectory units) still matches the real
            envelope-dims-mm length/width (converted mm->m); X/Y are
            naturally centered on the local origin already (autoparts.cad's
            +/-0.5-unit-square sketch convention -- see autoparts.scene's
            docstring)"
    (let [{:keys [positions dims]} (scene/scene-for sample-part-lot)
          extent (fn [axis] (- (apply max (map #(nth % axis) positions))
                                (apply min (map #(nth % axis) positions))))]
      (is (< (Math/abs (- (extent 0) (/ (:length-mm dims) 1000.0))) 1e-6))
      (is (< (Math/abs (- (extent 1) (/ (:width-mm dims) 1000.0))) 1e-6))
      ;; centered: min/max along X (and Y) are symmetric around 0.
      (is (< (Math/abs (+ (apply min (map #(nth % 0) positions))
                          (apply max (map #(nth % 0) positions))))
             1e-6)))))

(deftest scene-for-accepts-a-bare-mass-number-too
  (testing "run-pull-test's legacy bare-mass calling convention (a plain
            number, no part-lot map at all) also works through scene-for --
            autoparts.cad/envelope-dims-mm destructures a non-map value to
            all-nil, falling back to its disclosed defaults, and
            autoparts.robotics/run-pull-test normalizes the same bare number
            internally -- but a real part-lot map is the documented,
            preferred way to get a genuinely per-lot envelope"
    (let [sc (scene/scene-for 2.5)]
      (is (pos? (:vertex-count sc)))
      (is (pos? (:index-count sc))))))
