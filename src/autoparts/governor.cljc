(ns autoparts.governor
  "Auto-Parts Governor -- the independent compliance layer that earns
  the Auto-Parts Advisor the right to commit. The LLM has no notion
  of PPAP evidence law, whether a part-lot's own measured DPPM reject
  rate actually stays within its own recorded quality-agreement
  bounds, whether a process-capability-detected defect against the
  part-lot has actually stayed unresolved, or when an act stops being
  a draft and becomes a real-world robot part-lot shipment or PPAP-
  certificate issuance, so this MUST be a separate system able to
  *reject* a proposal and fall back to HOLD -- the auto-parts-
  manufacturer analog of `cloud-itonami-isic-6512`'s
  CasualtyGovernor.

  Seven checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them (you don't get to approve your way past a
  fabricated PPAP spec-basis, incomplete evidence, a robot CMM/torque/
  weld-inspection simulation that never ran or that independently
  re-checks out-of-tolerance, an out-of-spec part-lot, an unresolved
  process-capability defect, or a double shipment/certificate-
  issuance). The confidence/actuation gate is SOFT: it asks a human to
  look (low confidence / actuation), and the human may approve -- but
  see `autoparts.phase`: for `:stake :actuation/ship-part-lot`/
  `:actuation/issue-ppap-certificate` (a real safety-critical act) NO
  phase ever allows auto-commit either. Two independent layers agree
  that actuation is always a human call.

    1. Spec-basis                  -- did the PPAP evidence proposal
                                       cite an OFFICIAL source
                                       (`autoparts.facts`), or invent
                                       one?
    2. Evidence incomplete         -- for `:actuation/ship-part-lot`/
                                       `:actuation/issue-ppap-
                                       certificate`, has the part-lot
                                       actually been verified with a
                                       full PPAP evidence checklist
                                       (initial-process-studies-Cpk-
                                       report/measurement-system-
                                       analysis-report/part-submission-
                                       warrant/control-plan) on file?
    3. Robot simulation missing or
       independently out-of-
       tolerance                    -- for `:actuation/ship-part-lot`,
                                       has the robot CMM/torque/weld-
                                       inspection verification mission
                                       (`autoparts.robotics`) actually
                                       run and been recorded on the
                                       part-lot (`:robotics-sim-
                                       verified?`)? AND INDEPENDENTLY
                                       recompute whether the part-
                                       lot's own recorded critical-
                                       dimension-deviation reading
                                       falls out of its own recorded
                                       tolerance bounds (`autoparts.
                                       robotics/simulation-out-of-
                                       tolerance?`), ignoring whatever
                                       :passed? verdict the mission run
                                       itself stored -- the same
                                       'ground truth, not self-report'
                                       discipline check 4 below uses
                                       for DPPM.
    4. Part-lot DPPM out of range   -- for `:actuation/ship-part-lot`,
                                       INDEPENDENTLY recompute whether
                                       the part-lot's own measured
                                       DPPM reject rate falls outside
                                       its own recorded quality-
                                       agreement bounds (`autoparts.
                                       registry/part-lot-dppm-out-of-
                                       range?`) -- needs no proposal
                                       inspection or stored-verdict
                                       lookup at all. A further
                                       instance of this fleet's two-
                                       sided range check family (see
                                       `autoparts.registry`'s ns
                                       docstring for the lineage;
                                       `autoparts.robotics/dimensional-
                                       tolerance-out-of-range?` above
                                       is another).
    5. Process-capability defect
       unresolved                    -- reported by THIS proposal
                                       itself (a `:process-capability/
                                       screen` that just found an
                                       unresolved defect), or already
                                       on file for the part-lot
                                       (`:process-capability/screen`/
                                       `:actuation/issue-ppap-
                                       certificate`). Evaluated
                                       UNCONDITIONALLY (not scoped to
                                       a specific op), the SAME
                                       discipline `casualty.governor/
                                       sanctions-violations`/
                                       `automotive.governor/end-of-
                                       line-defect-unresolved-
                                       violations`/... (prior
                                       siblings)... established --
                                       exercised in tests/demo via
                                       `:process-capability/screen`
                                       DIRECTLY, not via an actuation
                                       op against an unscreened part-
                                       lot -- see this ns's own test
                                       suite.
    6. Confidence floor / actuation
       gate                          -- LLM confidence below threshold,
                                       OR the op is `:actuation/ship-
                                       part-lot`/`:actuation/issue-
                                       ppap-certificate` (REAL safety-
                                       critical acts) -> escalate.

  Two more guards, double-shipment/double-certificate-issuance
  prevention, are enforced but NOT listed as numbered HARD checks
  above because they need no upstream comparison at all --
  `already-shipped-violations`/`already-certified-violations` refuse
  to ship a part-lot action/issue a PPAP certificate for the SAME
  part-lot twice, off dedicated `:part-lot-shipped?`/`:ppap-
  certified?` facts (never a `:status` value) -- the SAME 'check a
  dedicated boolean, not status' discipline every prior sibling
  governor's guards establish, informed by `cloud-itonami-isic-6492`'s
  status-lifecycle bug (ADR-2607071320)."
  (:require [autoparts.facts :as facts]
            [autoparts.registry :as registry]
            [autoparts.robotics :as robotics]
            [autoparts.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Shipping a real part-lot to an OEM and issuing a real PPAP
  certificate are the two real-world actuation events this actor
  performs -- a two-member set, matching every prior dual-actuation
  sibling's shape."
  #{:actuation/ship-part-lot :actuation/issue-ppap-certificate})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:ppap-evidence/verify` (or actuation) proposal with no
  spec-basis citation is a HARD violation -- never invent a
  jurisdiction's PPAP requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:ppap-evidence/verify :actuation/ship-part-lot :actuation/issue-ppap-certificate} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案はPPAP要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:actuation/ship-part-lot`/`:actuation/issue-ppap-certificate`,
  the jurisdiction's required PPAP evidence (initial-process-studies-
  Cpk-report/measurement-system-analysis-report/part-submission-
  warrant/control-plan) must actually be satisfied -- do not trust the
  advisor's self-reported confidence alone."
  [{:keys [op subject]} st]
  (when (contains? #{:actuation/ship-part-lot :actuation/issue-ppap-certificate} op)
    (let [a (store/part-lot st subject)
          verification (store/ppap-verification-of st subject)]
      (when-not (and verification
                     (facts/required-evidence-satisfied?
                      (:jurisdiction a) (:checklist verification)))
        [{:rule :evidence-incomplete
          :detail "法域の必要PPAP書類(工程能力調査報告書/測定システム分析報告書/部品submission保証書/検査基準書等)が充足していない状態での提案"}]))))

(defn- robotics-simulation-violations
  "For `:actuation/ship-part-lot`: HARD hold if the robot CMM/torque/
  weld-inspection verification mission (`autoparts.robotics`) never
  ran and was recorded on the part-lot (`:robotics-sim-verified?`), OR
  if it did but an INDEPENDENT recompute of the part-lot's own
  critical-dimension-deviation fields (`autoparts.robotics/
  simulation-out-of-tolerance?`) says out-of-tolerance right now --
  never trusts the mission's own stored :passed? verdict alone, the
  same discipline `part-lot-dppm-out-of-range-violations` below uses
  for DPPM."
  [{:keys [op subject]} st]
  (when (= op :actuation/ship-part-lot)
    (let [a (store/part-lot st subject)]
      (cond
        (not (:robotics-sim-verified? a))
        [{:rule :robotics-simulation-missing
          :detail (str subject " のCMM/トルク/溶接検査ロボット検証ミッションが未実行・未合格")}]

        (robotics/simulation-out-of-tolerance? a)
        [{:rule :robotics-simulation-out-of-tolerance
          :detail (str subject " の重要寸法偏差実測値("
                       (:critical-dimension-deviation-actual a) ")が独立再検証で許容範囲["
                       (:critical-dimension-deviation-min a) "," (:critical-dimension-deviation-max a) "]を逸脱")}]))))

(defn- part-lot-dppm-out-of-range-violations
  "For `:actuation/ship-part-lot`, INDEPENDENTLY recompute whether the
  part-lot's own DPPM reject rate falls outside its own recorded
  quality-agreement bounds via `autoparts.registry/part-lot-dppm-out-
  of-range?` -- needs no proposal inspection or stored-verdict lookup
  at all, since its inputs are permanent ground-truth fields already
  on the part-lot."
  [{:keys [op subject]} st]
  (when (= op :actuation/ship-part-lot)
    (let [a (store/part-lot st subject)]
      (when (registry/part-lot-dppm-out-of-range? a)
        [{:rule :part-lot-dppm-out-of-range
          :detail (str subject " の実測DPPM(" (:dppm-actual a)
                      ")が品質協定範囲[" (:dppm-min a) "," (:dppm-max a) "]を逸脱")}]))))

(defn- process-capability-defect-unresolved-violations
  "An unresolved process-capability-detected defect -- reported by
  THIS proposal (e.g. a `:process-capability/screen` that itself just
  found one), or already on file in the store for the part-lot
  (`:process-capability/screen`/`:actuation/issue-ppap-certificate`)
  -- is a HARD, un-overridable hold. Evaluated UNCONDITIONALLY (not
  scoped to a specific op) so the screening op itself can HARD-hold
  on its own finding."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (= :unresolved (get-in proposal [:value :verdict]))
        part-lot-id (when (contains? #{:process-capability/screen :actuation/issue-ppap-certificate} op) subject)
        hit-on-file? (and part-lot-id (= :unresolved (:verdict (store/process-capability-screen-of st part-lot-id))))]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :process-capability-defect-unresolved
        :detail "未解決の工程能力欠陥がある状態でのPPAP証明書発行提案は進められない"}])))

(defn- already-shipped-violations
  "For `:actuation/ship-part-lot`, refuses to ship a part-lot action
  for the SAME part-lot twice, off a dedicated `:part-lot-shipped?`
  fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :actuation/ship-part-lot)
    (when (store/part-lot-already-shipped? st subject)
      [{:rule :already-shipped
        :detail (str subject " は既に出荷実行済み")}])))

(defn- already-certified-violations
  "For `:actuation/issue-ppap-certificate`, refuses to issue a PPAP
  certificate for the SAME part-lot twice, off a dedicated `:ppap-
  certified?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :actuation/issue-ppap-certificate)
    (when (store/part-lot-already-certified? st subject)
      [{:rule :already-certified
        :detail (str subject " は既にPPAP証明書発行済み")}])))

(defn check
  "Censors an Auto-Parts Advisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (robotics-simulation-violations request st)
                           (part-lot-dppm-out-of-range-violations request st)
                           (process-capability-defect-unresolved-violations request proposal st)
                           (already-shipped-violations request st)
                           (already-certified-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
