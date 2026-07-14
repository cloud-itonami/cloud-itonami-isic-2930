(ns autoparts.autopartsadvisor
  "Auto-Parts Advisor client -- the *contained intelligence node* for
  the auto-parts-manufacturing actor.

  It normalizes part-lot intake, drafts a per-jurisdiction PPAP
  evidence checklist, screens part-lots for an unresolved process-
  capability-detected defect, drafts the part-lot-shipment action,
  and drafts the PPAP-certificate-issuance action. CRITICAL: it is a
  smart-but-untrusted advisor. It returns a *proposal* (with a
  rationale + the fields it cited), never a committed record or a
  real robot shipment/PPAP-certificate issuance. Every output is
  censored downstream by `autoparts.governor` before anything touches
  the SSoT, and `:actuation/ship-part-lot`/`:actuation/issue-ppap-
  certificate` proposals NEVER auto-commit at any phase -- see README
  `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/ship-part-lot | :actuation/issue-ppap-certificate | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [autoparts.facts :as facts]
            [autoparts.registry :as registry]
            [autoparts.robotics :as robotics]
            [autoparts.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the part-lot, DPPM figures or jurisdiction. High
  confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "部品ロット記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :part-lot/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- verify-requirements
  "Per-jurisdiction PPAP evidence checklist draft. `:no-spec?` injects
  the failure mode we must defend against: proposing a checklist for
  a jurisdiction with NO official spec-basis in `autoparts.facts` --
  the Auto-Parts Governor must reject this (never invent a
  jurisdiction's requirements)."
  [db {:keys [subject no-spec?]}]
  (let [a (store/part-lot db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction a))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "autoparts.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :ppap-verification/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要PPAP書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 根拠規格: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :ppap-verification/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- screen-process-capability
  "Process-capability-defect screening draft.
  `:process-capability-defect-unresolved?` on the part-lot record
  injects the failure mode: the Auto-Parts Governor must HOLD,
  un-overridably, on any unresolved defect."
  [db {:keys [subject]}]
  (let [a (store/part-lot db subject)]
    (cond
      (nil? a)
      {:summary "対象部品ロット記録が見つかりません" :rationale "no part-lot record"
       :cites [] :effect :process-capability-screen/set :value {:part-lot-id subject :verdict :unknown}
       :stake nil :confidence 0.0}

      (true? (:process-capability-defect-unresolved? a))
      {:summary    (str (:part-lot-name a) ": 未解決の工程能力欠陥を検出")
       :rationale  "工程能力スクリーニングが未解決の欠陥を検出。人手確認とホールドが必須。"
       :cites      [:process-capability-check]
       :effect     :process-capability-screen/set
       :value      {:part-lot-id subject :verdict :unresolved}
       :stake      nil
       :confidence 0.95}

      :else
      {:summary    (str (:part-lot-name a) ": 未解決の工程能力欠陥なし")
       :rationale  "工程能力欠陥スクリーニング完了。"
       :cites      [:process-capability-check]
       :effect     :process-capability-screen/set
       :value      {:part-lot-id subject :verdict :resolved}
       :stake      nil
       :confidence 0.9})))

(defn- simulate-inspection-cell
  "Runs the robot CMM/torque/weld-inspection verification mission
  (`autoparts.robotics`) and drafts its result as a proposal. High
  confidence -- the mission itself is deterministic simulated
  telemetry derived from the part-lot's own recorded critical-
  dimension-deviation fields, not an LLM guess; the Auto-Parts
  Governor still independently re-derives :passed? from those same
  fields before any `:actuation/ship-part-lot` proposal may commit --
  see `autoparts.governor`'s `robotics-simulation-violations`."
  [db {:keys [subject]}]
  (let [a (store/part-lot db subject)]
    (if (nil? a)
      {:summary "対象部品ロット記録が見つかりません" :rationale "no part-lot record"
       :cites [] :effect :part-lot/upsert :value {:id subject :robotics-sim-verified? false}
       :stake nil :confidence 0.0}
      (let [{:keys [mission actions passed?]} (robotics/simulate-inspection-cell subject a)]
        {:summary    (str subject ": CMM/トルク/溶接検査ロボット検証ミッション " (if passed? "合格" "不合格"))
         :rationale  (str "mission=" (:mission/id mission) " actions=" (count actions)
                          " critical-dimension-deviation-actual=" (:critical-dimension-deviation-actual a))
         :cites      [(:mission/id mission)]
         :effect     :part-lot/upsert
         :value      {:id subject
                      :robotics-sim-verified? passed?
                      :robotics-sim-record {:mission-id (:mission/id mission)
                                            :actions (mapv #(dissoc % :action) actions)
                                            :passed? passed?}}
         :stake      nil
         :confidence 0.95}))))

(defn- propose-part-lot-shipment
  "Draft the actual PART-LOT-SHIPMENT action -- dispatching a real
  robot shipment/handling action on a safety-critical part-lot.
  ALWAYS `:stake :actuation/ship-part-lot` -- this is a REAL-WORLD
  safety-critical act, never a draft the actor may auto-run. See
  README `Actuation`: no phase ever adds this op to a phase's `:auto`
  set (`autoparts.phase`); the governor also always escalates on
  `:actuation/ship-part-lot`. Two independent layers agree,
  deliberately."
  [db {:keys [subject]}]
  (let [a (store/part-lot db subject)]
    {:summary    (str subject " 向け部品ロット出荷提案"
                      (when a (str " (part-lot=" (:part-lot-name a) ")")))
     :rationale  (if a
                   (str "dppm-actual=" (:dppm-actual a)
                        " spec=[" (:dppm-min a) "," (:dppm-max a) "]")
                   "部品ロット記録が見つかりません")
     :cites      (if a [subject] [])
     :effect     :part-lot/mark-shipped
     :value      {:part-lot-id subject}
     :stake      :actuation/ship-part-lot
     :confidence (if (and a (not (registry/part-lot-dppm-out-of-range? a))) 0.9 0.3)}))

(defn- propose-ppap-certificate
  "Draft the actual PPAP-CERTIFICATE action -- issuing a real PPAP
  submission / Certificate of Conformance certifying a part-lot as
  approved for production. ALWAYS `:stake :actuation/issue-ppap-
  certificate` -- this is a REAL-WORLD safety-critical act, never a
  draft the actor may auto-run. See README `Actuation`: no phase ever
  adds this op to a phase's `:auto` set (`autoparts.phase`); the
  governor also always escalates on `:actuation/issue-ppap-
  certificate`. Two independent layers agree, deliberately."
  [db {:keys [subject]}]
  (let [a (store/part-lot db subject)]
    {:summary    (str subject " 向けPPAP証明書発行提案"
                      (when a (str " (part-lot=" (:part-lot-name a) ")")))
     :rationale  (if a
                   "jurisdiction-evidence-checklist referenced"
                   "部品ロット記録が見つかりません")
     :cites      (if a [subject] [])
     :effect     :part-lot/mark-certified
     :value      {:part-lot-id subject}
     :stake      :actuation/issue-ppap-certificate
     :confidence (if a 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :part-lot/intake                             (normalize-intake db request)
    :ppap-evidence/verify                        (verify-requirements db request)
    :process-capability/screen                   (screen-process-capability db request)
    :robotics/simulate-inspection-cell           (simulate-inspection-cell db request)
    :actuation/ship-part-lot                     (propose-part-lot-shipment db request)
    :actuation/issue-ppap-certificate             (propose-ppap-certificate db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは自動車部品製造工場の出荷実行・PPAP証明書発行エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。説明や前置きは"
       "一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:part-lot/upsert|:ppap-verification/set|:process-capability-screen/set|"
       ":part-lot/mark-shipped|:part-lot/mark-certified) "
       "(:robotics/simulate-inspection-cell も :part-lot/upsert で "
       ":robotics-sim-verified? を提案する) "
       ":stake(:actuation/ship-part-lot か :actuation/issue-ppap-certificate か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :ppap-evidence/verify                        {:part-lot (store/part-lot st subject)}
    :process-capability/screen                   {:part-lot (store/part-lot st subject)}
    :robotics/simulate-inspection-cell            {:part-lot (store/part-lot st subject)}
    :actuation/ship-part-lot                      {:part-lot (store/part-lot st subject)}
    :actuation/issue-ppap-certificate              {:part-lot (store/part-lot st subject)}
    {:part-lot (store/part-lot st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Auto-Parts Governor
  escalates/holds -- an LLM hiccup can never auto-ship a part-lot
  action or auto-issue a PPAP certificate."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :autopartsadvisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
