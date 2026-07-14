(ns autoparts.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean part-lot through
  intake -> (an evidence-incomplete shipment attempt) -> PPAP evidence
  verification -> process-capability screening -> robot CMM/torque/
  weld-inspection mission -> part-lot-shipment proposal (always
  escalates) -> human approval -> commit, then through PPAP-
  certificate proposal (always escalates) -> human approval -> commit,
  then shows every HARD hold this actor defends against (a jurisdiction
  with no spec-basis, an actuation attempted before any PPAP evidence
  verification, an actuation attempted before the robot inspection
  mission ever ran, an out-of-spec DPPM reject rate, a robotics
  mission on file whose independent recheck disagrees, an unresolved
  process-capability defect screened directly via `:process-
  capability/screen` [never via an actuation op against an unscreened
  part-lot -- see this actor's own governor ns docstring / the lesson
  `parksafety`'s ADR-2607071922 Decision 5, and every prior sibling's
  ADR-0001 already recorded, most recently `automotive`'s], and a
  double part-lot-shipment/certificate-issuance of an already-
  processed part-lot) that never reach a human at all, and prints the
  audit ledger + the draft part-lot-shipment and PPAP-certificate
  records."
  (:require [langgraph.graph :as g]
            [autoparts.export :as export]
            [autoparts.store :as store]
            [autoparts.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :quality-engineer :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== part-lot/intake lot-1 (JPN, clean; DPPM within spec, no process-capability defect) ==")
    (println (exec! actor "t1" {:op :part-lot/intake :subject "lot-1"
                                :patch {:id "lot-1" :part-lot-name "Meridian Brake Pad Lot BP-2044"}} operator))

    (println "== actuation/ship-part-lot lot-1 before any PPAP evidence verification -> HARD hold (evidence-incomplete) ==")
    (println (exec! actor "t1b" {:op :actuation/ship-part-lot :subject "lot-1"} operator))

    (println "== ppap-evidence/verify lot-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :ppap-evidence/verify :subject "lot-1"} operator))
    (println (approve! actor "t2"))

    (println "== process-capability/screen lot-1 (clean; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :process-capability/screen :subject "lot-1"} operator))
    (println (approve! actor "t3"))

    (println "== robotics/simulate-inspection-cell lot-1 (robot CMM/torque/weld mission; escalates -- human approves) ==")
    (println (exec! actor "t3b" {:op :robotics/simulate-inspection-cell :subject "lot-1"} operator))
    (println (approve! actor "t3b"))

    (println "== actuation/ship-part-lot lot-1 (always escalates -- actuation/ship-part-lot) ==")
    (let [r (exec! actor "t4" {:op :actuation/ship-part-lot :subject "lot-1"} operator)]
      (println r)
      (println "-- human quality engineer approves --")
      (println (approve! actor "t4")))

    (println "== actuation/issue-ppap-certificate lot-1 (always escalates -- actuation/issue-ppap-certificate) ==")
    (let [r (exec! actor "t5" {:op :actuation/issue-ppap-certificate :subject "lot-1"} operator)]
      (println r)
      (println "-- human quality engineer approves --")
      (println (approve! actor "t5")))

    (println "== ppap-evidence/verify lot-2 (no spec-basis -> HARD hold) ==")
    (println (exec! actor "t6" {:op :ppap-evidence/verify :subject "lot-2" :no-spec? true} operator))

    (println "== ppap-evidence/verify lot-3 (escalates -- human approves; sets up the out-of-spec test) ==")
    (println (exec! actor "t7" {:op :ppap-evidence/verify :subject "lot-3"} operator))
    (println (approve! actor "t7"))

    (println "== actuation/ship-part-lot lot-3 before robotics simulation -> HARD hold (robotics-simulation-missing) ==")
    (println (exec! actor "t7b" {:op :actuation/ship-part-lot :subject "lot-3"} operator))

    (println "== robotics/simulate-inspection-cell lot-3 (real physics-2d proof-load pull-test simulation clears the floor; escalates -- human approves) ==")
    (println (exec! actor "t7c" {:op :robotics/simulate-inspection-cell :subject "lot-3"} operator))
    (println (approve! actor "t7c"))

    (println "== actuation/ship-part-lot lot-3 (850 dppm outside [0,300] quality-agreement bounds -> HARD hold) ==")
    (println (exec! actor "t8" {:op :actuation/ship-part-lot :subject "lot-3"} operator))

    (println "== actuation/ship-part-lot lot-5 (robotics-sim on file, but real physics-2d-simulated proof load falls below the minimum required floor on independent recheck -> HARD hold) ==")
    (println (exec! actor "t8b" {:op :ppap-evidence/verify :subject "lot-5"} operator))
    (println (approve! actor "t8b"))
    (println (exec! actor "t8c" {:op :actuation/ship-part-lot :subject "lot-5"} operator))

    (println "== process-capability/screen lot-4 (unresolved -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t9" {:op :process-capability/screen :subject "lot-4"} operator))

    (println "== actuation/ship-part-lot lot-1 AGAIN (double-shipment -> HARD hold) ==")
    (println (exec! actor "t10" {:op :actuation/ship-part-lot :subject "lot-1"} operator))

    (println "== actuation/issue-ppap-certificate lot-1 AGAIN (double-issuance -> HARD hold) ==")
    (println (exec! actor "t11" {:op :actuation/issue-ppap-certificate :subject "lot-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft part-lot-shipment records ==")
    (doseq [r (store/shipment-history db)] (println r))

    (println "== draft PPAP-certificate records ==")
    (doseq [r (store/certificate-history db)] (println r))

    (println "== social hand-off: audit package counts ==")
    (println (:counts (export/audit-package db)))
    (println "== social hand-off: CSV bundle keys ==")
    (println (keys (export/package->csv-bundle db)))))
