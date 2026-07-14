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
  explicitly builds the physics module DIRECTLY in this ns, taking a
  real git-coordinate dependency on `kotoba-lang/physics-2d` alone
  (see deps.edn), no BREP/CAM/webgpu-scene bridge.

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
  (:require [kotoba.robotics :as robotics]
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
  "Jaw AABB half-width along the pull axis (m) -- a small, fixed
  test-fixture-jaw footprint, not a per-lot geometry input (this ns has
  no CAD/BREP pipeline, unlike automotive's envelope-solid bridge)."
  0.01)

(def ^:const jaw-half-h-m 0.05)

(def ^:const fixture-half-w-m
  "Part-lot-side fixture AABB half-width (m) -- static anchor, never
  actually collides with anything (the jaw moves AWAY from it), present
  purely as a real Body2D so the simulated world honestly contains both
  sides of the joint being pulled apart."
  0.01)

(def ^:const fixture-half-h-m 0.05)

(def ^:const limit-boundary-half-w-m
  "Virtual limit-boundary AABB half-width (m) -- the 'end of tether'
  wall the jaw's approach is reframed against; see ns docstring."
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

(defn run-pull-test
  "Time-steps a REAL `physics-2d` world for the weld-joint/fastener
  proof-load pull test and returns:

    {:trajectory [{:tick :position :velocity} ...]   ; jaw body only
     :sim-peak-decel-mps2 n :sim-proof-load-force n
     :ticks n :dt n :test-speed-mps n :travel-to-failure-m n}

  `joint-mass-kg` is the part-lot's own recorded effective
  participating mass (moving jaw + locally-engaged specimen material).
  opts (all optional, for tuning/testing): `:test-speed-mps`,
  `:travel-to-failure-m`, `:initial-grip-slack-m`, `:dt` overrides
  (each defaults to this ns's own constant of the same name).

  `:sim-peak-decel-mps2` is the PEAK magnitude of tick-to-tick velocity
  change (along the pull axis) divided by `dt` -- derived from the
  actual simulated velocity trajectory, not invented.
  `:sim-proof-load-force` is `:sim-peak-decel-mps2 * joint-mass-kg`
  (Newtons) -- see ns docstring for why mass legitimately scales this
  reading (unlike automotive's mass-invariant `:sim-decel-g`)."
  [joint-mass-kg & [{v-opt :test-speed-mps travel-opt :travel-to-failure-m
                      slack-opt :initial-grip-slack-m dt-opt :dt}]]
  (let [v      (double (or v-opt test-speed-mps))
        travel (double (or travel-opt travel-to-failure-m))
        slack  (double (or slack-opt initial-grip-slack-m))
        dt     (double (or dt-opt (/ travel v)))
        fixture-x 0.0
        jaw-x0 (+ fixture-x fixture-half-w-m jaw-half-w-m)
        limit-boundary-x (+ jaw-x0 slack travel jaw-half-w-m limit-boundary-half-w-m)
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
                             :mass (double joint-mass-kg)
                             :restitution 0.0
                             :friction 0.0
                             :collider (p2d/make-aabb-collider jaw-half-w-m jaw-half-h-m)
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
     :sim-proof-load-force (* peak-decel-mps2 (double joint-mass-kg))
     :ticks (count trajectory)
     :dt dt
     :test-speed-mps v
     :travel-to-failure-m travel}))

(defn pull-test-telemetry-for
  "Runs the REAL `run-pull-test` `physics-2d` simulation for
  `part-lot`'s own recorded `:joint-mass-kg` and returns the actual
  simulated trajectory telemetry: `{:sim-proof-load-force n
  :sim-peak-decel-mps2 n :ticks n :dt n :test-speed-mps n
  :travel-to-failure-m n}`. Pure, deterministic -- the same
  `:joint-mass-kg` always reproduces the same telemetry."
  [part-lot]
  (select-keys (run-pull-test (:joint-mass-kg part-lot))
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
