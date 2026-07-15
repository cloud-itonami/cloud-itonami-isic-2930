(ns autoparts.cad
  "CAD bridge -- turns a part-lot's own recorded test-specimen/joint-
  coupon envelope dimensions (when on file) into a coarse BREP
  packaging envelope via `kotoba-lang/org-iso-10303`'s `brep.feature`
  parametric feature tree, then tessellates it (`brep.tessellate`) for
  `autoparts.robotics`'s pull-test AABB placement and `autoparts.scene`'s
  render bridge (ADR-2607160000, extending ADR-2607152000/ADR-2607151600's
  real-engineering-simulation pattern to this vertical -- a direct port
  of `kami-engine-vehicle-designer`'s `vdesign.cad` to this actor's own
  simpler, no-sibling-design-library case: unlike automotive, this
  vertical has no paired design-library repo, so this ns lives directly
  in `autoparts.*`, same reasoning ADR-2607152000 already used for
  putting the physics module directly in `autoparts.robotics`).

  Honest scope: this is a PACKAGING ENVELOPE -- a bounding-box
  approximation of the test-specimen/joint-coupon volume (length x
  width x height) -- not a modeled weld-nugget/fastener-thread surface,
  and not the actual part geometry being shipped (a part-lot's real
  product, e.g. a brake pad or wiring harness, is NOT what this ns
  models -- it models the TEST COUPON/JOINT SAMPLE used for the pull-
  test QA procedure `autoparts.robotics` simulates). `brep.feature/
  evaluate` currently only realizes an `:extrude` `:operation :new` as a
  fixed +/-0.5-unit-square cross-section extruded along the given
  direction/distance (sketch entities are not yet consumed by
  `evaluate`; revolve/fillet/chamfer/boolean are documented not-yet-
  implemented in `org-iso-10303`), so the cross-section here is
  realized at unit scale, then the resulting vertices are scaled non-
  uniformly to the target dimensions -- the SAME documented work-around
  `vdesign.cad` uses for the kernel's current maturity, not a new one
  invented for this ns.

  HONEST DESIGN CHOICE (ADR-2607160000, disclosed here rather than
  silently picked): `vdesign.cad` derives its envelope from TWO real
  vehicle-design fields already on the design record (`:wheelbase-m`/
  `:frontal-area-m2`). No per-lot linear-dimension field previously
  existed on a part-lot in this actor -- only `:joint-mass-kg`, a
  single scalar (ADR-2607152000). Two designs were considered for
  bridging that gap:

  (a) Back-derive length/width/height from `:joint-mass-kg` alone via
      an assumed material density AND an assumed coupon aspect-ratio --
      stacking two independent, unverifiable priors on top of each
      other to manufacture a false appearance of per-lot dimensional
      precision from a single scalar that carries no shape information
      at all. Rejected: this would look more precise than it honestly
      is.
  (b) A new, EXPLICITLY OPTIONAL `:specimen-length-mm`/
      `:specimen-width-mm`/`:specimen-height-mm` triple a part-lot MAY
      carry when a real coupon measurement is on file, falling back to
      a disclosed fixed default (chosen to exactly reproduce the SAME
      fixture-scale figures `autoparts.robotics` used as bare AABB
      constants before this ADR -- see `default-specimen-length-mm`/
      `default-specimen-width-mm`) when absent.

  (b) is the more honest choice and is what this ns implements: it
  never pretends a mass scalar alone can determine three independent
  length dimensions, and it makes the 'no real coupon measurement on
  file yet' case an explicit, disclosed fallback -- numerically
  identical to this actor's pre-ADR-2607160000 behavior -- rather than
  a hidden formula dressed up as precision. A part-lot record that DOES
  carry real `:specimen-*-mm` fields gets a genuinely per-lot envelope;
  one that doesn't gets the same honest default every part-lot
  effectively used before this ADR.

  Disclosed persistence gap: `autoparts.store/MemStore`'s `:part-lot/
  upsert` merges arbitrary keys, so `:specimen-*-mm` round-trips fine
  through MemStore. `autoparts.store/DatomicStore`'s schema does not
  yet declare `:specimen-*-mm` attributes, so those fields are NOT
  persisted through a DatomicStore round-trip today -- a real,
  disclosed limitation, not silently papered over. `envelope-dims-mm`'s
  fallback defaults keep every downstream consumer (`autoparts.
  robotics`, `autoparts.scene`, `autoparts.motionplan`) fully
  functional either way; extending the Datomic schema to persist real
  coupon measurements is straightforward follow-up work, not done here."
  (:require [brep.feature :as feat]
            [brep.tessellate :as tess]))

(def ^:const default-specimen-length-mm
  "Fallback specimen-envelope length (mm, along the pull-test travel
  axis) when a part-lot carries no real `:specimen-length-mm` --
  DELIBERATELY chosen to exactly reproduce `autoparts.robotics`'s prior
  `jaw-half-w-m` figure (0.01 m half-width = 0.02 m = 20 mm full
  length), so a part-lot with no coupon measurement on file gets the
  SAME AABB size this actor already used before ADR-2607160000 -- a
  plausible small structural-joint coupon length, not a measured
  value."
  20.0)

(def ^:const default-specimen-width-mm
  "Fallback specimen-envelope width (mm, lateral) -- see
  `default-specimen-length-mm`; DELIBERATELY chosen to exactly
  reproduce `autoparts.robotics`'s prior `jaw-half-h-m` figure (0.05 m
  half-height = 0.10 m = 100 mm full width)."
  100.0)

(def ^:const default-specimen-height-mm
  "Fallback specimen-envelope height (mm) -- NOT consumed by
  `autoparts.robotics`'s 2D pull-test physics (only length/width feed
  the travel-axis/lateral AABB half-extents, mirroring `vdesign.
  simphysics/vehicle-half-extents-m`'s own length/width-only use of
  `vdesign.cad/envelope-dims-mm`); kept only so the tessellated BREP
  envelope is a genuine 3D box rather than a degenerate flat sheet, and
  so `autoparts.motionplan`'s working-height derivation has a real
  height figure to read. A plausible small coupon-stock thickness, not
  a measured value."
  3.0)

(defn envelope-dims-mm
  "{:length-mm :width-mm :height-mm} for `part-lot`: its OWN recorded
  `:specimen-length-mm`/`:specimen-width-mm`/`:specimen-height-mm` when
  present (a genuine, per-lot coupon/joint envelope measurement), or
  this ns's disclosed fixture-scale defaults when absent -- see ns
  docstring for why this is the more honest of the two designs
  considered. `part-lot` may be `nil`/`{}` (every field then falls
  back to its default)."
  [part-lot]
  (let [{:keys [specimen-length-mm specimen-width-mm specimen-height-mm]} part-lot]
    {:length-mm (double (or specimen-length-mm default-specimen-length-mm))
     :width-mm  (double (or specimen-width-mm default-specimen-width-mm))
     :height-mm (double (or specimen-height-mm default-specimen-height-mm))}))

(defn- scale-point [[x y z] sx sy sz]
  [(* x sx) (* y sy) (* z sz)])

(defn envelope-solid
  "Build+evaluate a single-sketch/extrude BREP feature tree sized to
  `part-lot`'s envelope dims (`envelope-dims-mm`). Returns {:solid
  :edges :vertices :dims}. Direct port of `vdesign.cad/envelope-solid`
  -- see that ns's docstring for exactly why the cross-section is
  realized at unit scale then non-uniformly scaled. Throws ex-info only
  if evaluation fails, which it does not for this single-extrude case
  (per `brep.feature/evaluate`'s documented base-feature support)."
  [part-lot]
  (let [{:keys [length-mm width-mm height-mm] :as dims} (envelope-dims-mm part-lot)
        ;; sketch on XY (the footprint plane); extrude along Z by
        ;; height-mm -- matches vdesign.cad's CNC-adjacent convention
        ;; (Z is the depth/thickness axis, XY is the footprint plane),
        ;; even though this ns has no CAM/toolpath consumer of its own.
        sketch  (feat/sketch-feature 1 (feat/sketch-plane-xy) [])
        extrude (feat/extrude-feature 2 1 [0.0 0.0 1.0] height-mm :new)
        tree    (-> (feat/feature-tree)
                    (feat/add-feature sketch)
                    (feat/add-feature extrude))
        [status result] (feat/evaluate tree)]
    (when (not= status :ok)
      (throw (ex-info "brep envelope evaluation failed" {:result result :part-lot part-lot})))
    (let [[solid edges vertices] result
          scaled (mapv #(update % :point scale-point length-mm width-mm 1.0) vertices)]
      {:solid solid :edges edges :vertices scaled :dims dims})))

(defn envelope-mesh
  "Tessellate an `envelope-solid` result into {:positions [[x y z] ...]
  :indices [i0 i1 i2 ...]} -- the shape `autoparts.scene/scene-for`
  consumes. Direct port of `vdesign.cad/envelope-mesh`."
  [{:keys [solid edges vertices]}]
  (let [[positions indices] (tess/tessellate-solid solid edges vertices)]
    {:positions positions :indices indices}))
