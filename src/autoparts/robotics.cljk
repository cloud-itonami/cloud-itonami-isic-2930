(ns autoparts.robotics
  "Robot-executed dimensional/process-capability verification -- the
  concrete, actor-level realization of ADR-2607011000's robotics
  premise and ADR-2607142800's robotics-process-simulation pattern
  (established by `cloud-itonami-isic-2910`'s `automotive.robotics`,
  and named there as follow-up work for THIS vertical, isic-2930) for
  THIS actor's own `autoparts.facts` requirement that a part-lot-
  shipment proposal cite an initial-process-studies (Cpk/Ppk) report
  actually on file -- not merely a self-reported checklist string.

  ADR-2607152000 (extending ADR-2607151600, which did the same for
  `cloud-itonami-isic-2910`/`automotive.robotics`) rewires this ns
  onto a REAL engineering simulation instead of a synthetic,
  deterministic field comparison: a genuine time-stepped `physics-2d`
  rigid-body simulation of a WELD-JOINT / FASTENER PROOF-LOAD PULL
  TEST -- a real automotive-parts QA procedure (pulling a welded/
  fastened joint apart at a controlled rate until a peak load is
  reached, then comparing that peak load against the joint's own
  minimum required spec). Unlike automotive (which paired with a
  SIBLING design-library repo, `kami-engine-vehicle-designer`, because
  that pairing already existed via `vehicle-design-link`,
  ADR-2607083500), this vertical has no such sibling -- ADR-2607152000
  explicitly built the physics module DIRECTLY in this ns, taking a
  real git-coordinate dependency on `kotoba-lang/physics-2d` alone
  (see deps.edn).

  ADR-2607160000 EXTENDS this ns with a real CAD/BREP bridge, closing
  the gap this ns's docstring used to disclose ('this ns has no CAD/
  BREP pipeline, unlike automotive's envelope-solid bridge'): the
  `:jaw` body's AABB half-extents are now genuinely derived from
  `autoparts.cad/envelope-dims-mm`'s tessellated packaging-envelope
  dims for THIS part-lot (mirroring `vdesign.simphysics/vehicle-half-
  extents-m`'s own read of `vdesign.cad/envelope-solid`), instead of
  being bare fixed constants. Honest, disclosed limit this does NOT
  close: `autoparts.cad`'s envelope is still a coarse bounding BOX (see
  that ns's docstring), not the actual weld-nugget/fastener-thread
  geometry -- and, as documented below at `run-pull-test`, this
  geometry only changes the simulated WORLD's spatial layout
  (`:trajectory` positions), never `:sim-peak-decel-mps2`/`:sim-proof-
  load-force` themselves, a real, verified property of this ns's
  'boxcar' single-tick collision technique (see below), not an
  oversight. `:fixture`/`:limit-boundary` remain FIXED test-rig
  constants, unchanged -- mirroring how `vdesign.simphysics` only ever
  derives the MOVING body (the vehicle) from CAD and leaves its static
  barrier fixed; `:limit-boundary` in particular has no physical
  counterpart at all (see its own docstring below), so there is nothing
  real for CAD to size it against.

  HONEST REINTERPRETATION TECHNIQUE (mirrors automotive's own
  disclosed 'reaching end-of-tether, not literally crashing into a
  barrier' trick in `vdesign.simphysics`): `physics-2d`'s `world-step`
  ONLY natively resolves bodies that are APPROACHING/colliding -- it
  has no notion of a body SEPARATING under tension, so there is no
  direct way to simulate 'pull the joint apart until it snaps' with
  this engine's collision-only impulse resolver. This ns reframes the
  SAME physical event as an approach instead: `:jaw` (the test-fixture
  jaw gripping one side of the joint) starts right beside `:fixture`
  (a static body anchoring the part-lot's own side of the joint) and
  moves steadily AWAY from it at a real, controlled pull-test rate --
  but a THIRD, static `:limit-boundary` body is placed exactly
  `travel-to-failure-m` (the joint's own real compliance/give distance
  before it separates) beyond the jaw's start. As the jaw travels, it
  is really the JOINT running out of give -- `physics-2d` only knows
  how to render that as the jaw's leading face reaching the
  limit-boundary's near face, at which point its native inelastic
  (restitution 0) collision resolution zeroes the jaw's velocity in a
  SINGLE tick -- exactly the 'joint holds, then suddenly arrests/
  snaps' event a real pull test exhibits at peak load. The peak
  deceleration read off that tick, times the part-lot's own recorded
  effective participating mass (`:joint-mass-kg` -- the moving jaw +
  the locally-engaged specimen material), is `:sim-proof-load-force`
  (Newtons) -- REAL, derived from the actual simulated trajectory,
  never invented.

  Disclosed engineering priors (this ns's own, not measured facts --
  same discipline as automotive's `frontal-area-m2` table):

  - `test-speed-mps` models a genuine, established test category --
    high-rate/dynamic tensile-shear testing of automotive structural
    weld/fastener joints (qualifying joint behavior for crash-relevant
    loading rates, distinct from a slow quasi-static hand-tool proof
    check), run at a representative low-single-digit m/s rate -- NOT
    the mm/min crosshead speed a slow quasi-static check would use.
    Running this SAME single-tick 'boxcar' technique at a genuinely
    slow quasi-static rate would derive a physically negligible
    reading (peak-decel = test-speed^2 / travel-to-failure scales with
    the SQUARE of speed, so a slow rate is the wrong physical regime
    for a discrete-collision technique). Automotive's own crash test
    is well-suited to this same technique precisely BECAUSE a barrier
    crash genuinely is a fast, dynamic event -- a real weld/fastener
    proof-load pull test is not, in general, UNLESS it is specifically
    the dynamic/high-rate qualification variant this ns models.
  - `travel-to-failure-m` is a representative low-single-digit-
    millimeter joint compliance/give distance -- a real, disclosed
    order of magnitude for resistance-spot-weld button-pull-out /
    fastener thread-engagement failure displacement in tensile-shear
    testing.
  - `initial-grip-slack-m` is a small, real, disclosed test-fixture
    grip-seating/alignment slack the jaw travels BEFORE the joint
    itself begins to bear load -- present only so the simulated
    trajectory captures a real pre-load approach phase, not just the
    single stopping tick (mirrors automotive's `default-gap-m`).
  - `min-proof-load-n` is a newly-defined, clearly-disclosed real-world
    floor (ADR-2607152000 explicitly allows this when neither this
    vertical's DPPM nor its now-removed critical-dimension-deviation
    fields fit a FORCE reading) -- a plausible minimum acceptable
    tensile-shear/proof load for a single structural spot-weld or
    small-to-medium structural fastener joint in automotive body/
    chassis applications (low-single-digit kN), NOT a literal
    transcription of one specific named standard's number.

  Unlike automotive's crash model (where `:sim-decel-g` is PROVABLY
  mass-invariant against a static barrier -- colliding with an
  immovable anchor, the impulse's velocity change does not depend on
  the moving body's own mass), the quantity reported HERE is a FORCE
  (Newtons), so `:joint-mass-kg` DOES directly scale
  `:sim-proof-load-force` (force = mass x deceleration) -- intentional,
  not an oversight: a real load-cell force reading legitimately
  depends on the physical scale of the joint/fixture under test, not
  an accident of chosen units.

  `proof-load-out-of-tolerance?` independently re-derives the
  part-lot's OWN recorded `:sim-proof-load-force` against
  `min-proof-load-n`, never from the mission's self-reported result --
  the SAME 'ground truth, not self-report' discipline `autoparts.
  registry/part-lot-dppm-out-of-range?` established for DPPM reject
  rate. `autoparts.governor`'s `robotics-simulation-violations` calls
  this ns's independent recheck, never the stored :passed? value,
  before any `:actuation/ship-part-lot` proposal may commit.

  Pure data + pure functions -- no real robot I/O, no network.
  `physics-2d/world-step` is itself a pure, fixed-timestep integrator
  (no wall-clock/IO), so this stays exactly as offline/deterministic
  as every other sibling namespace in this actor -- tests and the demo
  run without a network.

  Honest scope (ADR-2607152000, mirroring ADR-2607151600 for this
  simpler, no-design-library case): this DOES model a real
  time-stepped `physics-2d` rigid-body trajectory for the pull-test
  event. It does NOT model: joint material/stiffness (`physics-2d` has
  no force-deflection/spring model at all -- the joint's 'give' is
  encoded purely as a travel DISTANCE, not a compliance curve), 3D
  geometry (2D projection only, same disclosed limit as automotive), a
  real load-cell/DAQ connection, or a real robot controller -- still
  simulation, not control, the same 'policy, not control' boundary
  `kotoba.robotics`'s docstring already establishes."
  (:require [autoparts.cad :as cad]
            [kotoba.robotics :as robotics]
            [physics-2d :as p2d]))

;; ---------------------------------------------------------------------------
;; Platform shims (mirrors physics-2d's own private sqrt*/abs*/signum* style,
;; keeping this ns portable .cljc -- unlike vdesign.simphysics's raw Math/ceil
;; + Math/abs, which are JVM-only and would break a ClojureScript consumer).
;; ---------------------------------------------------------------------------

(defn- abs* [x] (if (neg? x) (- x) x))

(defn- ceil* [x]
  #?(:clj  (Math/ceil (double x))
     :cljs (js/Math.ceil x)))

(def mission-actions
  "The three-step CMM/torque/weld-inspection verification mission every
  part-lot walks through before `:actuation/ship-part-lot` is
  proposable. All :sense/:actuate at :none/:low safety -- dimensional/
  fastener verification/QA sensing on a stationary part-lot, not the
  moving-shipment actuation that is `:actuation/ship-part-lot` itself
  (always :safety-critical -- see `autoparts.governor`)."
  [{:step :cmm-dimensional-scan       :kind :sense   :safety :none}
   {:step :fastener-torque-check      :kind :actuate :safety :low}
   {:step :weld-joint-ultrasonic-scan :kind :sense   :safety :none}])

;; ---------------------- real pull-test physics constants --------------------

(def ^:const test-speed-mps
  "Controlled jaw pull-rate (m/s) -- see ns docstring: a representative
  dynamic/high-rate tensile-shear test speed for automotive structural
  weld/fastener joints, not a literal quasi-static crosshead mm/min
  transcription (which this single-tick 'boxcar' technique cannot
  honestly render as a meaningful force reading -- see docstring)."
  2.0)

(def ^:const travel-to-failure-m
  "The joint's own real compliance/give distance (m) before it
  separates -- see ns docstring: a representative low-single-digit-
  millimeter prior for spot-weld/fastener tensile-shear failure
  displacement."
  0.002)

(def ^:const initial-grip-slack-m
  "Test-fixture grip-seating/alignment slack (m) the jaw travels before
  the joint itself begins to bear load -- present only so the
  trajectory captures a real pre-load approach phase, mirroring
  automotive's `vdesign.simphysics/default-gap-m`."
  0.0005)

(def ^:const jaw-half-w-m
  "Jaw AABB half-width along the pull axis (m) -- ADR-2607160000: no
  longer read directly by `run-pull-test` (superseded by `autoparts.
  cad`-derived per-part-lot dims, see `specimen-half-extents-m`
  below), retained as a disclosed reference figure -- `autoparts.cad/
  default-specimen-length-mm` is DELIBERATELY defined to reproduce this
  exact half-width (20 mm full length / 2 = 0.01 m) when a part-lot
  carries no real `:specimen-length-mm`, so a part-lot with nothing on
  file gets the SAME jaw size this ns used before this ADR."
  0.01)

(def ^:const jaw-half-h-m
  "Jaw AABB half-height (m), lateral -- see `jaw-half-w-m`; `autoparts.
  cad/default-specimen-width-mm` reproduces this exact figure (100 mm
  full width / 2 = 0.05 m)."
  0.05)

(def ^:const fixture-half-w-m
  "Part-lot-side fixture AABB half-width (m) -- static anchor, never
  actually collides with anything (the jaw moves AWAY from it), present
  purely as a real Body2D so the simulated world honestly contains both
  sides of the joint being pulled apart. ADR-2607160000: unlike the
  jaw, this stays a FIXED test-rig constant, not CAD-derived -- mirrors
  `vdesign.simphysics` leaving its static barrier fixed while only the
  moving vehicle body derives from CAD."
  0.01)

(def ^:const fixture-half-h-m 0.05)

(def ^:const limit-boundary-half-w-m
  "Virtual limit-boundary AABB half-width (m) -- the 'end of tether'
  wall the jaw's approach is reframed against; see ns docstring.
  ADR-2607160000: stays FIXED, never CAD-derived -- this body has no
  physical counterpart at all (it is a pure math device standing in for
  the joint running out of give), so there is nothing real for CAD to
  size it against."
  0.01)

(def ^:const limit-boundary-half-h-m 0.05)

(def ^:const settle-ticks
  "Extra ticks appended after the jaw is expected to reach the
  limit-boundary, so the trajectory also captures post-contact
  settling. `physics-2d`'s positional correction removes 80% of any
  remaining overlap per tick (`resolve-contact`'s `0.8` factor), so
  residual overlap after `settle-ticks` further ticks is `0.2^settle-
  ticks` of whatever it was at first contact -- 15 ticks converges to
  ~3e-11 (same rationale/constant as automotive's `vdesign.simphysics/
  settle-ticks`, a genuine physics-2d engine property, not re-derived
  here)."
  15)

(def ^:const min-proof-load-n
  "Real, disclosed minimum acceptable proof load (N) for a single
  structural spot-weld or small-to-medium structural fastener joint in
  automotive body/chassis applications -- see ns docstring. 3500 N
  (3.5 kN) sits in the plausible low-single-digit-kN range commonly
  cited for this class of joint; a newly-defined bound, not a literal
  transcription of one specific named standard's number (ADR-2607152000
  explicitly allows this when no existing on-file field fits a FORCE
  reading better)."
  3500.0)

;; ------------------------------ real simulation ------------------------------

(defn- as-part-lot-map
  "Normalizes `run-pull-test`'s first argument: a bare number is a
  legacy/no-coupon-geometry caller and is treated as `{:joint-mass-kg
  n}` (so `autoparts.cad/envelope-dims-mm` falls back to its disclosed
  defaults, below); a map (a real part-lot record, optionally carrying
  `:specimen-length-mm`/`:specimen-width-mm`) is passed through
  unchanged."
  [part-lot-or-mass]
  (if (map? part-lot-or-mass) part-lot-or-mass {:joint-mass-kg part-lot-or-mass}))

(defn- specimen-half-extents-m
  "AABB half-extents (m) for the `:jaw` body, from `autoparts.cad/
  envelope-dims-mm`'s REAL tessellated dims (mm) for `part-lot` --
  travel-axis half-width = length/2, lateral half-height = width/2.
  Direct port of `vdesign.simphysics/vehicle-half-extents-m`'s length/
  width-only reading of `vdesign.cad`. `envelope-dims-mm` always
  returns SOME dims (a part-lot's own real `:specimen-*-mm` fields when
  present, this ns's disclosed fixture-scale defaults when absent --
  see `autoparts.cad`'s docstring), so this always succeeds; it is the
  INPUT (whether `part-lot` carries real coupon measurements) that
  varies, not this function's availability."
  [part-lot]
  (let [{:keys [length-mm width-mm]} (cad/envelope-dims-mm part-lot)]
    {:half-w (/ length-mm 2000.0)
     :half-h (/ width-mm 2000.0)}))

(defn run-pull-test
  "Time-steps a REAL `physics-2d` world for the weld-joint/fastener
  proof-load pull test and returns:

    {:trajectory [{:tick :position :velocity} ...]   ; jaw body only
     :sim-peak-decel-mps2 n :sim-proof-load-force n
     :ticks n :dt n :test-speed-mps n :travel-to-failure-m n}

  `part-lot-or-mass` is EITHER the part-lot's own recorded effective
  participating mass (moving jaw + locally-engaged specimen material,
  a bare number -- legacy/no-coupon-geometry callers) OR the full
  part-lot map (with `:joint-mass-kg` and, optionally, a real
  `:specimen-length-mm`/`:specimen-width-mm`/`:specimen-height-mm`
  coupon-envelope measurement -- ADR-2607160000). Either way the
  `:jaw` body's AABB is sized via `specimen-half-extents-m`
  (`autoparts.cad`-derived, real-or-disclosed-default); `:fixture`/
  `:limit-boundary` always use their own fixed constants (see those
  defs' docstrings for why).
  opts (all optional, for tuning/testing): `:test-speed-mps`,
  `:travel-to-failure-m`, `:initial-grip-slack-m`, `:dt` overrides
  (each defaults to this ns's own constant of the same name).

  `:sim-peak-decel-mps2` is the PEAK magnitude of tick-to-tick velocity
  change (along the pull axis) divided by `dt` -- derived from the
  actual simulated velocity trajectory, not invented.
  `:sim-proof-load-force` is `:sim-peak-decel-mps2 * joint-mass-kg`
  (Newtons) -- see ns docstring for why mass legitimately scales this
  reading (unlike automotive's mass-invariant `:sim-decel-g`).

  GEOMETRY-INVARIANCE, disclosed (mirrors automotive's own disclosed
  mass-invariance of `:sim-decel-g`): the `:jaw`/`:fixture`/`:limit-
  boundary` placement below is deliberately arranged so the FACE-TO-
  FACE gap the jaw must close is always exactly `slack + travel`,
  regardless of any body's AABB half-extents (they cancel out of the
  placement algebra by construction -- widening/narrowing the jaw only
  shifts every body's CENTER position by the same amount, never the
  face-to-face gap). Consequently `:sim-peak-decel-mps2`/`:sim-proof-
  load-force`/`:ticks`/`:dt` are IDENTICAL whether `part-lot-or-mass`
  carries real coupon dims or falls back to defaults -- only
  `:trajectory`'s absolute `:position` values (and, if a caller reads
  them, `:jaw`'s AABB size) change. This is a genuine, verified
  property of this ns's single-tick 'boxcar' collision technique (the
  jaw always covers the SAME `slack + travel` gap at the SAME `dt`),
  not a bug and not something CAD integration was expected to change --
  a real load-cell force reading in this idealization is driven by
  test SPEED, joint MASS, and the joint's own compliance/give
  (`travel-to-failure-m`), never by the fixture/specimen's outer
  bounding-box size."
  [part-lot-or-mass & [{v-opt :test-speed-mps travel-opt :travel-to-failure-m
                         slack-opt :initial-grip-slack-m dt-opt :dt}]]
  (let [part-lot (as-part-lot-map part-lot-or-mass)
        joint-mass-kg (double (:joint-mass-kg part-lot))
        v      (double (or v-opt test-speed-mps))
        travel (double (or travel-opt travel-to-failure-m))
        slack  (double (or slack-opt initial-grip-slack-m))
        dt     (double (or dt-opt (/ travel v)))
        {:keys [half-w half-h]} (specimen-half-extents-m part-lot)
        fixture-x 0.0
        jaw-x0 (+ fixture-x fixture-half-w-m half-w)
        limit-boundary-x (+ jaw-x0 slack travel half-w limit-boundary-half-w-m)
        approach-m (+ slack travel)
        ticks (long (+ settle-ticks (long (ceil* (/ approach-m (* v dt))))))
        fixture (p2d/make-body {:position [fixture-x 0.0]
                                 :velocity [0.0 0.0]
                                 :mass 0.0
                                 :restitution 0.0
                                 :friction 0.0
                                 :collider (p2d/make-aabb-collider fixture-half-w-m fixture-half-h-m)
                                 :user-data :fixture})
        jaw (p2d/make-body {:position [jaw-x0 0.0]
                             :velocity [v 0.0]
                             :mass joint-mass-kg
                             :restitution 0.0
                             :friction 0.0
                             :collider (p2d/make-aabb-collider half-w half-h)
                             :user-data :jaw})
        limit-boundary (p2d/make-body {:position [limit-boundary-x 0.0]
                                        :velocity [0.0 0.0]
                                        :mass 0.0
                                        :restitution 0.0
                                        :friction 0.0
                                        :collider (p2d/make-aabb-collider limit-boundary-half-w-m limit-boundary-half-h-m)
                                        :user-data :limit-boundary})
        w0 (p2d/world-new [0.0 0.0])
        [w1 _fixture-id] (p2d/world-add w0 fixture)
        [w2 jaw-id] (p2d/world-add w1 jaw)
        [w3 _limit-id] (p2d/world-add w2 limit-boundary)
        worlds (reductions (fn [w _] (p2d/world-step w dt)) w3 (range ticks))
        trajectory (mapv (fn [tick world]
                            (let [b (nth (:bodies world) jaw-id)]
                              {:tick tick :position (:position b) :velocity (:velocity b)}))
                          (range (count worlds)) worlds)
        vxs (mapv (comp first :velocity) trajectory)
        peak-decel-mps2 (->> (map (fn [va vb] (abs* (/ (- vb va) dt))) vxs (rest vxs))
                              (reduce max 0.0))]
    {:trajectory trajectory
     :sim-peak-decel-mps2 peak-decel-mps2
     :sim-proof-load-force (* peak-decel-mps2 joint-mass-kg)
     :ticks (count trajectory)
     :dt dt
     :test-speed-mps v
     :travel-to-failure-m travel}))

(defn pull-test-telemetry-for
  "Runs the REAL `run-pull-test` `physics-2d` simulation for `part-lot`
  (its own recorded `:joint-mass-kg`, plus, when present, its own real
  `:specimen-length-mm`/`:specimen-width-mm`/`:specimen-height-mm`
  coupon-envelope measurement -- ADR-2607160000, `run-pull-test`'s
  `part-lot-or-mass` accepts the full map) and returns the actual
  simulated trajectory telemetry: `{:sim-proof-load-force n
  :sim-peak-decel-mps2 n :ticks n :dt n :test-speed-mps n
  :travel-to-failure-m n}`. Pure, deterministic -- the same `part-lot`
  always reproduces the same telemetry. Per `run-pull-test`'s disclosed
  geometry-invariance, `:sim-proof-load-force`/`:sim-peak-decel-mps2`
  themselves are driven by `:joint-mass-kg` (and test speed/travel),
  NOT by the coupon envelope's size -- see that fn's docstring."
  [part-lot]
  (select-keys (run-pull-test part-lot)
               [:sim-proof-load-force :sim-peak-decel-mps2 :ticks :dt
                :test-speed-mps :travel-to-failure-m]))

(defn proof-load-out-of-tolerance?
  "Ground-truth check: does `part-lot`'s own recorded
  `:sim-proof-load-force` (the REAL `run-pull-test` trajectory
  telemetry already on file for this part-lot -- see
  `pull-test-telemetry-for`) fall below `min-proof-load-n`? Needs no
  mission run -- its inputs are permanent fields already on the
  part-lot, the same shape `autoparts.registry/part-lot-dppm-out-of-
  range?` uses for DPPM."
  [{:keys [sim-proof-load-force]}]
  (and (number? sim-proof-load-force)
       (< sim-proof-load-force min-proof-load-n)))

(defn simulate-inspection-cell
  "Run the robot CMM/torque/weld-inspection verification mission for
  `part-lot-id` (`part-lot` is the full part-lot record, incl.
  `:joint-mass-kg`). Actually runs the REAL engine: `pull-test-
  telemetry-for` -- the actual `physics-2d`-stepped weld-joint/fastener
  proof-load pull-test trajectory (`:sim-proof-load-force`/
  `:sim-peak-decel-mps2`).

  Returns {:mission .. :actions [{:action .. :proof ..} ..] :passed?
  bool :sim-proof-load-force n :sim-peak-decel-mps2 n}. Deterministic:
  :passed? is derived from the part-lot's OWN recorded `:joint-mass-kg`
  via the REAL simulated trajectory (`proof-load-out-of-tolerance?`),
  never invented or randomized -- `kotoba.robotics` mandates no
  network/IO, and a repeatable simulation is what makes the governor's
  independent recheck meaningful."
  [part-lot-id part-lot]
  (let [telemetry (pull-test-telemetry-for part-lot)
        out-of-range? (proof-load-out-of-tolerance? (merge part-lot telemetry))
        reading (if out-of-range? :out-of-tolerance :nominal)
        mission (robotics/mission (str "mission-" part-lot-id "-inspection-verify")
                                   :robot/inspection-cell-1
                                   :dimensional-inspection-verification
                                   :boundaries {:station "end-of-line-inspection-cell"}
                                   :max-steps (count mission-actions))
        actions (mapv (fn [{:keys [step kind safety]}]
                        (let [a (robotics/action (str (:mission/id mission) "-" (name step))
                                                  (:mission/id mission) kind safety
                                                  :params {:step step :part-lot-id part-lot-id})]
                          {:action a
                           :proof (robotics/telemetry-proof (:mission/id mission) step reading
                                                             :provenance :simulated)}))
                      mission-actions)]
    {:mission mission
     :actions actions
     :passed? (not out-of-range?)
     :sim-proof-load-force (:sim-proof-load-force telemetry)
     :sim-peak-decel-mps2 (:sim-peak-decel-mps2 telemetry)}))

(defn simulation-out-of-tolerance?
  "Independent ground-truth recheck for the governor: does `part-lot`'s
  OWN current, on-file real `physics-2d`-simulated proof-load telemetry
  (`:sim-proof-load-force`) fall out of tolerance right now? Ignores
  whatever :passed? verdict a prior mission run stored -- identical in
  spirit to `autoparts.registry/part-lot-dppm-out-of-range?`'s refusal
  to trust a proposal's self-report."
  [part-lot]
  (proof-load-out-of-tolerance? part-lot))
