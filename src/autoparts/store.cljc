(ns autoparts.store
  "SSoT for the auto-parts-manufacturing actor, behind a `Store`
  protocol so the backend is a swap, not a rewrite -- the same seam
  every prior `cloud-itonami-isic-*` actor in this fleet uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/autoparts/store_contract_test.clj), which is the whole point:
  the actor, the Auto-Parts Governor and the audit ledger never know
  which SSoT they run on.

  Like `automotive.store`'s dual vehicle-dispatch/conformity-
  certificate history and every other dual-actuation sibling before
  it, this actor has TWO actuation events (shipping a part-lot to an
  OEM, issuing a PPAP certificate) acting on the SAME entity (a part-
  lot), each with its OWN history collection, sequence counter and
  dedicated double-actuation-guard boolean (`:part-lot-shipped?`/
  `:ppap-certified?`, never a `:status` value) -- the same discipline
  every prior sibling governor's guards establish, informed by
  `cloud-itonami-isic-6492`'s status-lifecycle bug (ADR-2607071320).

  The ledger stays append-only on every backend: 'which part-lot was
  screened for an unresolved process-capability defect, which part-
  lot shipment was dispatched, which PPAP certificate was issued, on
  what jurisdictional basis, approved by whom' is always a query over
  an immutable log -- the audit trail a community trusting an auto-
  parts manufacturer needs, and the evidence a manufacturer needs if
  a shipment or PPAP-certificate decision is later disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [autoparts.registry :as registry]
            [autoparts.robotics :as robotics]
            [langchain.db :as d]))

(defprotocol Store
  (part-lot [s id])
  (all-part-lots [s])
  (process-capability-screen-of [s part-lot-id] "committed process-capability screening verdict for a part-lot, or nil")
  (ppap-verification-of [s part-lot-id] "committed PPAP evidence verification, or nil")
  (ledger [s])
  (shipment-history [s] "the append-only part-lot-shipment history (autoparts.registry drafts)")
  (certificate-history [s] "the append-only PPAP-certificate history (autoparts.registry drafts)")
  (next-shipment-sequence [s jurisdiction] "next shipment-number sequence for a jurisdiction")
  (next-certificate-sequence [s jurisdiction] "next certificate-number sequence for a jurisdiction")
  (part-lot-already-shipped? [s part-lot-id] "has this part-lot already been shipped?")
  (part-lot-already-certified? [s part-lot-id] "has this part-lot's PPAP certificate already been issued?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-part-lots [s part-lots] "replace/seed the part-lot directory (map id->part-lot)"))

;; ----------------------------- demo data -----------------------------

(defn- with-proof-load-telemetry
  "Merges REAL weld-joint/fastener proof-load pull-test telemetry onto
  a demo part-lot's base fields -- `autoparts.robotics/pull-test-
  telemetry-for` actually runs `run-pull-test`'s `physics-2d`-stepped
  simulation for this part-lot's own `:joint-mass-kg` (ADR-2607152000),
  so even the 'already on file' seed data (as if from an earlier real
  pull-test report) is genuinely simulation-derived, never hand-typed
  doubles."
  [base]
  (merge base (select-keys (robotics/pull-test-telemetry-for base)
                           [:sim-proof-load-force :sim-peak-decel-mps2])))

(defn demo-data
  "A small, self-contained part-lot set covering both actuation
  lifecycles (shipping a part-lot, issuing a PPAP certificate) so the
  actor + tests run offline. `:joint-mass-kg` (ADR-2607152000) is a
  permanent part-lot-design field (like `:dppm-actual`);
  `:sim-proof-load-force`/`:sim-peak-decel-mps2` are the REAL
  `autoparts.robotics/run-pull-test`-computed telemetry for that field
  (`with-proof-load-telemetry`), the ground truth `autoparts.robotics/
  simulation-out-of-tolerance?` independently rechecks. lot-5 (a
  fastener-tightening lot) is DELIBERATELY recorded with a much lighter
  `:joint-mass-kg` (0.6 kg, the scale of a small trim fastener) than a
  structural joint of this kind should carry -- a genuine design-record
  inconsistency (no real structural fastener/weld joint this actor
  ships would spec down to a trim-clip-scale test mass) that the real,
  re-run simulation catches on independent recheck even though
  `:robotics-sim-verified?` was seeded `true` (\"already on file\", i.e.
  someone/something marked it passed without this real check ever
  having run) -- the auto-parts-manufacturer analog of automotive's
  vehicle-5 misclassified pickup. lot-1..4's `:joint-mass-kg` values
  (2.2-2.6 kg) are all genuinely consistent structural-joint test
  masses, which all clear the real proof-load floor with margin (see
  `autoparts.robotics/min-proof-load-n`)."
  []
  {:part-lots
   (into {}
         (map (fn [v] [(:id v) (with-proof-load-telemetry v)]))
         [{:id "lot-1" :part-lot-name "Meridian Brake Pad Lot BP-2044"
           :dppm-actual 45 :dppm-min 0 :dppm-max 300
           :joint-mass-kg 2.5
           :process-capability-defect-unresolved? false
           :robotics-sim-verified? false :robotics-sim-record nil
           :part-lot-shipped? false :ppap-certified? false
           :jurisdiction "JPN" :status :intake}
          {:id "lot-2" :part-lot-name "Atlas Wiring Harness Lot WH-1187"
           :dppm-actual 45 :dppm-min 0 :dppm-max 300
           :joint-mass-kg 2.2
           :process-capability-defect-unresolved? false
           :robotics-sim-verified? false :robotics-sim-record nil
           :part-lot-shipped? false :ppap-certified? false
           :jurisdiction "ATL" :status :intake}
          {:id "lot-3" :part-lot-name "田中シートフレーム・ロット SF-215"
           :dppm-actual 850 :dppm-min 0 :dppm-max 300
           :joint-mass-kg 2.6
           :process-capability-defect-unresolved? false
           :robotics-sim-verified? false :robotics-sim-record nil
           :part-lot-shipped? false :ppap-certified? false
           :jurisdiction "JPN" :status :intake}
          {:id "lot-4" :part-lot-name "佐藤スタンピングパネル・ロット SP-330"
           :dppm-actual 45 :dppm-min 0 :dppm-max 300
           :joint-mass-kg 2.4
           :process-capability-defect-unresolved? true
           :robotics-sim-verified? false :robotics-sim-record nil
           :part-lot-shipped? false :ppap-certified? false
           :jurisdiction "JPN" :status :intake}
          {:id "lot-5" :part-lot-name "鈴木ファスナー締結ロット FT-118"
           :dppm-actual 45 :dppm-min 0 :dppm-max 300
           :joint-mass-kg 0.6
           :process-capability-defect-unresolved? false
           :robotics-sim-verified? true :robotics-sim-record nil
           :part-lot-shipped? false :ppap-certified? false
           :jurisdiction "JPN" :status :intake}])})

;; ----------------------------- shared commit logic -----------------------------

(defn- ship-part-lot!
  "Backend-agnostic `:part-lot/mark-shipped` -- looks up the part-lot
  via the protocol and drafts the part-lot-shipment record, and
  returns {:result .. :part-lot-patch ..} for the caller to persist."
  [s part-lot-id]
  (let [a (part-lot s part-lot-id)
        seq-n (next-shipment-sequence s (:jurisdiction a))
        result (registry/register-part-lot-shipment part-lot-id (:jurisdiction a) seq-n)]
    {:result result
     :part-lot-patch {:part-lot-shipped? true
                       :shipment-number (get result "shipment_number")}}))

(defn- issue-ppap-certificate!
  "Backend-agnostic `:part-lot/mark-certified` -- looks up the
  part-lot via the protocol and drafts the PPAP-certificate record,
  and returns {:result .. :part-lot-patch ..} for the caller to
  persist."
  [s part-lot-id]
  (let [a (part-lot s part-lot-id)
        seq-n (next-certificate-sequence s (:jurisdiction a))
        result (registry/register-ppap-certificate part-lot-id (:jurisdiction a) seq-n)]
    {:result result
     :part-lot-patch {:ppap-certified? true
                       :certificate-number (get result "certificate_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (part-lot [_ id] (get-in @a [:part-lots id]))
  (all-part-lots [_] (sort-by :id (vals (:part-lots @a))))
  (process-capability-screen-of [_ id] (get-in @a [:process-capability-screens id]))
  (ppap-verification-of [_ part-lot-id] (get-in @a [:verifications part-lot-id]))
  (ledger [_] (:ledger @a))
  (shipment-history [_] (:shipments @a))
  (certificate-history [_] (:certificates @a))
  (next-shipment-sequence [_ jurisdiction] (get-in @a [:shipment-sequences jurisdiction] 0))
  (next-certificate-sequence [_ jurisdiction] (get-in @a [:certificate-sequences jurisdiction] 0))
  (part-lot-already-shipped? [_ part-lot-id] (boolean (get-in @a [:part-lots part-lot-id :part-lot-shipped?])))
  (part-lot-already-certified? [_ part-lot-id] (boolean (get-in @a [:part-lots part-lot-id :ppap-certified?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :part-lot/upsert
      (swap! a update-in [:part-lots (:id value)] merge value)

      :ppap-verification/set
      (swap! a assoc-in [:verifications (first path)] payload)

      :process-capability-screen/set
      (swap! a assoc-in [:process-capability-screens (first path)] payload)

      :part-lot/mark-shipped
      (let [part-lot-id (first path)
            {:keys [result part-lot-patch]} (ship-part-lot! s part-lot-id)
            jurisdiction (:jurisdiction (part-lot s part-lot-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:shipment-sequences jurisdiction] (fnil inc 0))
                       (update-in [:part-lots part-lot-id] merge part-lot-patch)
                       (update :shipments registry/append result))))
        result)

      :part-lot/mark-certified
      (let [part-lot-id (first path)
            {:keys [result part-lot-patch]} (issue-ppap-certificate! s part-lot-id)
            jurisdiction (:jurisdiction (part-lot s part-lot-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:certificate-sequences jurisdiction] (fnil inc 0))
                       (update-in [:part-lots part-lot-id] merge part-lot-patch)
                       (update :certificates registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-part-lots [s part-lots] (when (seq part-lots) (swap! a assoc :part-lots part-lots)) s))

(defn seed-db
  "A MemStore seeded with the demo part-lot set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :verifications {} :process-capability-screens {} :ledger []
                           :shipment-sequences {} :shipments []
                           :certificate-sequences {} :certificates []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (verification/process-capability-screen
  payloads, ledger facts, shipment/certificate records) are stored as
  EDN strings so `langchain.db` doesn't expand them into
  sub-entities -- the same convention every sibling actor's store
  uses."
  {:part-lot/id                       {:db/unique :db.unique/identity}
   :verification/part-lot-id          {:db/unique :db.unique/identity}
   :process-capability-screen/part-lot-id {:db/unique :db.unique/identity}
   :ledger/seq                        {:db/unique :db.unique/identity}
   :shipment/seq                      {:db/unique :db.unique/identity}
   :certificate/seq                   {:db/unique :db.unique/identity}
   :shipment-sequence/jurisdiction    {:db/unique :db.unique/identity}
   :certificate-sequence/jurisdiction {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- part-lot->tx [{:keys [id part-lot-name dppm-actual dppm-min dppm-max
                              joint-mass-kg sim-proof-load-force sim-peak-decel-mps2
                              process-capability-defect-unresolved? robotics-sim-verified? robotics-sim-record
                              part-lot-shipped? ppap-certified?
                              jurisdiction status shipment-number certificate-number]}]
  (cond-> {:part-lot/id id}
    part-lot-name                               (assoc :part-lot/part-lot-name part-lot-name)
    dppm-actual                                 (assoc :part-lot/dppm-actual dppm-actual)
    dppm-min                                    (assoc :part-lot/dppm-min dppm-min)
    dppm-max                                    (assoc :part-lot/dppm-max dppm-max)
    joint-mass-kg                                (assoc :part-lot/joint-mass-kg joint-mass-kg)
    sim-proof-load-force                         (assoc :part-lot/sim-proof-load-force sim-proof-load-force)
    (some? sim-peak-decel-mps2)                  (assoc :part-lot/sim-peak-decel-mps2 sim-peak-decel-mps2)
    (some? process-capability-defect-unresolved?) (assoc :part-lot/process-capability-defect-unresolved? process-capability-defect-unresolved?)
    (some? robotics-sim-verified?)               (assoc :part-lot/robotics-sim-verified? robotics-sim-verified?)
    (some? robotics-sim-record)                  (assoc :part-lot/robotics-sim-record (enc robotics-sim-record))
    (some? part-lot-shipped?)                   (assoc :part-lot/part-lot-shipped? part-lot-shipped?)
    (some? ppap-certified?)                     (assoc :part-lot/ppap-certified? ppap-certified?)
    jurisdiction                                (assoc :part-lot/jurisdiction jurisdiction)
    status                                      (assoc :part-lot/status status)
    shipment-number                             (assoc :part-lot/shipment-number shipment-number)
    certificate-number                          (assoc :part-lot/certificate-number certificate-number)))

(def ^:private part-lot-pull
  [:part-lot/id :part-lot/part-lot-name :part-lot/dppm-actual
   :part-lot/dppm-min :part-lot/dppm-max
   :part-lot/joint-mass-kg :part-lot/sim-proof-load-force :part-lot/sim-peak-decel-mps2
   :part-lot/process-capability-defect-unresolved? :part-lot/robotics-sim-verified? :part-lot/robotics-sim-record
   :part-lot/part-lot-shipped? :part-lot/ppap-certified?
   :part-lot/jurisdiction :part-lot/status :part-lot/shipment-number :part-lot/certificate-number])

(defn- pull->part-lot [m]
  (when (:part-lot/id m)
    {:id (:part-lot/id m) :part-lot-name (:part-lot/part-lot-name m)
     :dppm-actual (:part-lot/dppm-actual m)
     :dppm-min (:part-lot/dppm-min m)
     :dppm-max (:part-lot/dppm-max m)
     :joint-mass-kg (:part-lot/joint-mass-kg m)
     :sim-proof-load-force (:part-lot/sim-proof-load-force m)
     :sim-peak-decel-mps2 (:part-lot/sim-peak-decel-mps2 m)
     :process-capability-defect-unresolved? (boolean (:part-lot/process-capability-defect-unresolved? m))
     :robotics-sim-verified? (boolean (:part-lot/robotics-sim-verified? m))
     :robotics-sim-record (dec* (:part-lot/robotics-sim-record m))
     :part-lot-shipped? (boolean (:part-lot/part-lot-shipped? m))
     :ppap-certified? (boolean (:part-lot/ppap-certified? m))
     :jurisdiction (:part-lot/jurisdiction m) :status (:part-lot/status m)
     :shipment-number (:part-lot/shipment-number m) :certificate-number (:part-lot/certificate-number m)}))

(defrecord DatomicStore [conn]
  Store
  (part-lot [_ id]
    (pull->part-lot (d/pull (d/db conn) part-lot-pull [:part-lot/id id])))
  (all-part-lots [_]
    (->> (d/q '[:find [?id ...] :where [?e :part-lot/id ?id]] (d/db conn))
         (map #(pull->part-lot (d/pull (d/db conn) part-lot-pull [:part-lot/id %])))
         (sort-by :id)))
  (process-capability-screen-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?aid
                :where [?k :process-capability-screen/part-lot-id ?aid] [?k :process-capability-screen/payload ?p]]
              (d/db conn) id)))
  (ppap-verification-of [_ part-lot-id]
    (dec* (d/q '[:find ?p . :in $ ?aid
                :where [?a :verification/part-lot-id ?aid] [?a :verification/payload ?p]]
              (d/db conn) part-lot-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (shipment-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :shipment/seq ?s] [?e :shipment/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (certificate-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :certificate/seq ?s] [?e :certificate/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-shipment-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :shipment-sequence/jurisdiction ?j] [?e :shipment-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (next-certificate-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :certificate-sequence/jurisdiction ?j] [?e :certificate-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (part-lot-already-shipped? [s part-lot-id]
    (boolean (:part-lot-shipped? (part-lot s part-lot-id))))
  (part-lot-already-certified? [s part-lot-id]
    (boolean (:ppap-certified? (part-lot s part-lot-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :part-lot/upsert
      (d/transact! conn [(part-lot->tx value)])

      :ppap-verification/set
      (d/transact! conn [{:verification/part-lot-id (first path) :verification/payload (enc payload)}])

      :process-capability-screen/set
      (d/transact! conn [{:process-capability-screen/part-lot-id (first path) :process-capability-screen/payload (enc payload)}])

      :part-lot/mark-shipped
      (let [part-lot-id (first path)
            {:keys [result part-lot-patch]} (ship-part-lot! s part-lot-id)
            jurisdiction (:jurisdiction (part-lot s part-lot-id))
            next-n (inc (next-shipment-sequence s jurisdiction))]
        (d/transact! conn
                     [(part-lot->tx (assoc part-lot-patch :id part-lot-id))
                      {:shipment-sequence/jurisdiction jurisdiction :shipment-sequence/next next-n}
                      {:shipment/seq (count (shipment-history s)) :shipment/record (enc (get result "record"))}])
        result)

      :part-lot/mark-certified
      (let [part-lot-id (first path)
            {:keys [result part-lot-patch]} (issue-ppap-certificate! s part-lot-id)
            jurisdiction (:jurisdiction (part-lot s part-lot-id))
            next-n (inc (next-certificate-sequence s jurisdiction))]
        (d/transact! conn
                     [(part-lot->tx (assoc part-lot-patch :id part-lot-id))
                      {:certificate-sequence/jurisdiction jurisdiction :certificate-sequence/next next-n}
                      {:certificate/seq (count (certificate-history s)) :certificate/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-part-lots [s part-lots]
    (when (seq part-lots) (d/transact! conn (mapv part-lot->tx (vals part-lots)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:part-lots ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [part-lots]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-part-lots s part-lots))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo part-lot set -- the Datomic-
  backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
