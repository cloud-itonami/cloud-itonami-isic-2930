(ns autoparts.scene
  "Bridge from `autoparts.cad`'s tessellated specimen-envelope mesh +
  `autoparts.robotics/run-pull-test`'s per-tick physics trajectory
  (`:jaw` body only) into the vertex/index/per-frame-transform data
  shape `kotoba-lang/webgpu`'s `kami.webgpu.mesh` executor's REAL,
  working `upload-mesh!`/`render-frame!` functions already consume
  (ADR-2607160000 -- direct port of `kami-engine-vehicle-designer`'s
  `vdesign.scene` reference pattern, ADR-2607151600, to this vertical's
  own simpler no-sibling-design-library case; see `autoparts.cad`/
  `autoparts.robotics` docstrings for that same porting rationale).

  `:positions`/`:normals`/`:indices` in `scene-for`'s result are
  DIRECTLY the shape `kami.webgpu.mesh/upload-mesh!` destructures
  (`{:keys [positions normals indices uvs morph-target-deltas joints
  weights]}`, all but `:positions`/`:normals`/`:indices` optional) --
  `(select-keys (scene-for part-lot) [:positions :normals :indices])`
  is a drop-in `geometry` argument for that function today. `:frames`'s
  per-entry `:transform` map (`{:translation [x y z] :rotation [rx ry
  rz] :scale [sx sy sz]}`) is DIRECTLY the shape `kami.webgpu.mesh/
  model-matrix` (and `render-frame!`'s optional trailing `transform`
  arg, handed straight to `model-matrix`) expects -- one `:frames`
  entry per `autoparts.robotics/run-pull-test` trajectory tick.

  Two REAL, disclosed gaps close this from being a byte-for-byte
  drop-in -- the SAME two gaps `vdesign.scene` closes, ported here
  verbatim because the underlying mismatch is identical:

  1. `autoparts.cad/envelope-mesh` produces `{:positions :indices}`
     only -- no `:normals`. `kami.webgpu.mesh/upload-mesh!` requires
     `:normals` (the same length as `:positions` -- a mandatory vertex
     attribute the shader reads, not optional like `:uvs`/skin/morph).
     `face-normals` below computes REAL per-triangle flat normals
     (cross product of each triangle's own two edges) to close this gap
     -- not a placeholder/constant normal.
  2. `autoparts.cad/envelope-mesh`'s positions are in MILLIMETERS
     (`autoparts.cad/envelope-dims-mm` derives `:length-mm`/`:width-mm`/
     `:height-mm`, and `envelope-solid`'s `scale-point` scales the base
     sketch by those mm figures directly) while `autoparts.robotics/
     run-pull-test`'s trajectory positions are in METERS (`physics-2d`
     is unit-agnostic, but `autoparts.robotics` chose meters -- see
     that namespace's `specimen-half-extents-m`, which divides by
     2000.0 to go mm -> half-extent-in-meters). Combining raw mm vertex
     geometry with a meter-scale per-frame translation would place the
     mesh ~1000x too large relative to its own motion. `mesh->m` below
     converts the tessellated positions to meters to close this real
     unit mismatch.

  Same box-footprint-centering property `vdesign.scene` documents
  (verified, not assumed, by `scene_test.clj` below): `autoparts.cad/
  envelope-solid` tessellates from `brep.feature`'s documented
  +/-0.5-unit-square sketch, so the footprint is ALREADY centered on
  the local origin in X/Y (min-x = -length/2, max-x = +length/2) --
  only Z spans `[0,height]` uncentered, the extrude direction. No XY
  shift is needed or applied; only the mm->m unit conversion.

  Remaining, honest limitation (same as `vdesign.scene`'s): `kami.
  webgpu.mesh` itself is a `.cljs`-only WebGPU executor (`js/
  Float32Array`, real GPU device/buffer calls) -- actually calling
  `upload-mesh!`/`render-frame!` needs a ClojureScript/browser host
  loading this namespace's output and iterating `:frames`, which this
  JVM-`.cljc` actor repo (no browser here) cannot execute, and
  `kotoba-lang/webgpu` is deliberately NOT a runtime dependency of this
  repo (see deps.edn). The DATA SHAPE this namespace produces is
  genuinely, verifiably compatible with that function's real input
  contract (see `scene_test.clj`); wiring it into a live canvas is the
  small host-side step that remains, and is NOT claimed to be done
  here.

  Also disclosed: every frame's `:rotation` is `[0 0 0]` and `:scale`
  is `[1 1 1]` -- `physics-2d`'s `Body2D` carries NO orientation/
  angular state at all (translation-only rigid body), a real property
  of the underlying solver, not something simplified away by this
  bridge."
  (:require [autoparts.cad :as cad]
            [autoparts.robotics :as robotics]))

(defn- v-sub [[ax ay az] [bx by bz]] [(- bx ax) (- by ay) (- bz az)])

(defn- v-cross [[ax ay az] [bx by bz]]
  [(- (* ay bz) (* az by))
   (- (* az bx) (* ax bz))
   (- (* ax by) (* ay bx))])

(defn- v-length [[x y z]]
  #?(:clj  (Math/sqrt (double (+ (* x x) (* y y) (* z z))))
     :cljs (js/Math.sqrt (+ (* x x) (* y y) (* z z)))))

(defn- flat-normal
  "Real geometric face normal for triangle `a b c` -- cross product of
  two edges, normalized. Falls back to `[0 0 1]` only for a degenerate
  (zero-area) triangle."
  [a b c]
  (let [n (v-cross (v-sub a b) (v-sub a c))
        len (v-length n)]
    (if (pos? len)
      (mapv #(/ % len) n)
      [0.0 0.0 1.0])))

(defn face-normals
  "Per-vertex flat (face) normals for `positions`/`indices`
  (`autoparts.cad/envelope-mesh`'s output shape) -- REAL geometric
  normals computed from each triangle's own 3 vertices, not a
  placeholder or constant. Safe to assign one flat normal per triangle-
  vertex here because `brep.tessellate/tessellate-solid` gives every
  BREP face (this envelope's sketch/extrude box faces) its own PRIVATE
  vertex range -- no vertex index is shared between two faces with
  different normals for this shape, so writing a vertex's normal once
  per triangle it appears in never produces a wrong final value (same
  reasoning `vdesign.scene/face-normals` documents, unchanged here)."
  [positions indices]
  (let [tris (partition 3 indices)]
    (reduce
     (fn [normals [ia ib ic]]
       (let [n (flat-normal (nth positions ia) (nth positions ib) (nth positions ic))]
         (-> normals (assoc ia n) (assoc ib n) (assoc ic n))))
     (vec (repeat (count positions) [0.0 0.0 1.0]))
     tris)))

(defn- mesh->m
  "Converts `autoparts.cad`'s millimeter-scale tessellated positions to
  meters, matching `autoparts.robotics`'s meter-scale trajectory
  positions -- see namespace docstring, gap 2. A uniform positive scale
  never changes face-normal directions, so callers may compute
  `face-normals` before or after this conversion; `scene-for` converts
  first, for positions that are already in the same units as
  `:frames`' translations."
  [positions]
  (mapv (fn [[x y z]] [(/ x 1000.0) (/ y 1000.0) (/ z 1000.0)]) positions))

(defn scene-for
  "Builds
    {:positions [...] :normals [...] :indices [...]
     :frames [{:tick n :transform {:translation [x y z]
                                   :rotation [0.0 0.0 0.0]
                                   :scale [1.0 1.0 1.0]}} ...]
     :vertex-count n :index-count n :dims {...}}
  for `part-lot` -- the real tessellated specimen envelope
  (`autoparts.cad/envelope-solid`/`envelope-mesh`), unit-converted to
  meters and given real face normals, plus one frame per `autoparts.
  robotics/run-pull-test` trajectory tick (`sim-opts` is passed
  straight through to `run-pull-test`). `:dims` is `autoparts.cad`'s
  own millimeter-scale `:dims` (`:length-mm`/`:width-mm`/
  `:height-mm`), kept as informational metadata, NOT the unit
  `:positions` is in. `part-lot` should be the full part-lot map (a
  bare mass number also works, per `run-pull-test`'s own contract, but
  then carries no real coupon geometry). See namespace docstring for
  exactly which fields are a direct drop-in for `kami.webgpu.mesh`
  today vs. the disclosed adapter gaps this namespace closes."
  [part-lot & [sim-opts]]
  (let [solid (cad/envelope-solid part-lot)
        {:keys [positions indices]} (cad/envelope-mesh solid)
        positions (mesh->m positions)
        normals (face-normals positions indices)
        sim (robotics/run-pull-test part-lot sim-opts)
        frames (mapv (fn [{:keys [tick position]}]
                       {:tick tick
                        :transform {:translation [(first position) (second position) 0.0]
                                    :rotation [0.0 0.0 0.0]
                                    :scale [1.0 1.0 1.0]}})
                     (:trajectory sim))]
    {:positions positions
     :normals normals
     :indices indices
     :frames frames
     :vertex-count (count positions)
     :index-count (count indices)
     :dims (:dims solid)}))
