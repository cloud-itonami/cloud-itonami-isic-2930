(ns autoparts.robotics
  "Robot-executed dimensional/process-capability verification -- the
  concrete, actor-level realization of ADR-2607011000's robotics
  premise and ADR-2607142800's robotics-process-simulation pattern
  (established by `cloud-itonami-isic-2910`'s `automotive.robotics`,
  and named there as follow-up work for THIS vertical, isic-2930) for
  THIS actor's own `autoparts.facts` requirement that a part-lot-
  shipment proposal cite an initial-process-studies (Cpk/Ppk) report
  actually on file -- not merely a self-reported checklist string.

  A robot mission (`kotoba.robotics/mission`) walks the part-lot
  through three :sense/:actuate steps -- CMM (Coordinate Measuring
  Machine) dimensional scan, fastener/joint torque check, weld-joint
  ultrasonic scan -- built with `kotoba.robotics/action` +
  `kotoba.robotics/telemetry-proof`, and reports an overall :passed?
  verdict. `simulation-out-of-tolerance?` independently re-derives
  that verdict from the part-lot's OWN recorded critical-dimension-
  deviation fields, never from the mission's self-reported result --
  the SAME 'ground truth, not self-report' discipline `autoparts.
  registry/part-lot-dppm-out-of-range?` established for DPPM reject
  rate (itself continuing the fleet's two-sided range-check family;
  see that ns's docstring). `autoparts.governor`'s `robotics-
  simulation-violations` calls this ns's independent recheck, never
  the stored :passed? value, before any `:actuation/ship-part-lot`
  proposal may commit.

  Pure data + pure functions -- no real robot I/O, no network.
  `kotoba.robotics` is itself \"policy, not control\"; this namespace
  simulates what a real robot inspection cell would report,
  deterministically, from the part-lot's own recorded fields, so
  tests and the demo run offline exactly like every other sibling
  namespace in this actor."
  (:require [kotoba.robotics :as robotics]))

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

(defn dimensional-tolerance-out-of-range?
  "Ground-truth check: does `part-lot`'s own recorded
  :critical-dimension-deviation-actual fall outside its own recorded
  [:critical-dimension-deviation-min :critical-dimension-deviation-max]
  bounds? Needs no mission run or proposal inspection -- its inputs
  are permanent fields already on the part-lot, the same shape
  `autoparts.registry/part-lot-dppm-out-of-range?` uses for DPPM."
  [{:keys [critical-dimension-deviation-actual
           critical-dimension-deviation-min
           critical-dimension-deviation-max]}]
  (and (number? critical-dimension-deviation-actual)
       (number? critical-dimension-deviation-min)
       (number? critical-dimension-deviation-max)
       (or (< critical-dimension-deviation-actual critical-dimension-deviation-min)
           (> critical-dimension-deviation-actual critical-dimension-deviation-max))))

(defn simulate-inspection-cell
  "Run the robot CMM/torque/weld-inspection verification mission for
  `part-lot-id` (`part-lot` is the full part-lot record, incl.
  critical-dimension-deviation-* fields). Returns {:mission ..
  :actions [{:action .. :proof ..} ..] :passed? bool}. Deterministic:
  :passed? is derived from the part-lot's OWN recorded critical-
  dimension-deviation fields via `dimensional-tolerance-out-of-
  range?`, never invented or randomized -- `kotoba.robotics` mandates
  no network/IO, and a repeatable simulation is what makes the
  governor's independent recheck (`simulation-out-of-tolerance?`)
  meaningful."
  [part-lot-id part-lot]
  (let [out-of-range? (dimensional-tolerance-out-of-range? part-lot)
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
     :passed? (not out-of-range?)}))

(defn simulation-out-of-tolerance?
  "Independent ground-truth recheck for the governor: does `part-lot`'s
  OWN current critical-dimension-deviation fields fall out of range
  right now? Ignores whatever :passed? verdict a prior mission run
  stored -- identical in spirit to `autoparts.registry/part-lot-dppm-
  out-of-range?`'s refusal to trust a proposal's self-report."
  [part-lot]
  (dimensional-tolerance-out-of-range? part-lot))
