(ns autoparts.facts
  "Per-jurisdiction PPAP (Production Part Approval Process) evidence
  catalog -- the G2-style spec-basis table the Auto-Parts Governor
  checks every `:ppap-evidence/verify` proposal against.

  Unlike `cloud-itonami-isic-2910`'s vehicle type-approval (a
  GOVERNMENT-mandated regime -- MLIT/NHTSA/DVSA/KBA statute), PPAP is
  an OEM-customer-driven INDUSTRY quality-management requirement: the
  authorities cited below (AIAG, VDA QMC, SMMT, JAPIA/JASO) publish
  and steward the standard, they do not enact law. This catalog cites
  each authority and standard honestly for what it actually is --
  never inflates an industry requirement into a statute, never
  invents one.

  Coverage is reported HONESTLY: a jurisdiction not in this table has
  NO spec-basis. Seed values cite official PPAP/IATF-16949-adjacent
  standards bodies; this is a starting catalog, not a survey of every
  market or every OEM's own supplement.")

(def catalog
  {"USA" {:name "United States"
          :owner-authority "AIAG (Automotive Industry Action Group)"
          :legal-basis "AIAG PPAP Manual (4th edition) / IATF 16949:2016 (reference; industry quality-management standard, not a government statute)"
          :national-spec "AIAG PPAP submission levels 1-5 for production-part approval"
          :provenance "https://www.aiag.org/"
          :required-evidence ["Initial process studies (Cpk/Ppk) report"
                              "Measurement System Analysis (MSA) report"
                              "Part Submission Warrant (PSW)"
                              "Control plan"]}
   "DEU" {:name "Germany"
          :owner-authority "VDA QMC (Verband der Automobilindustrie -- Qualitäts Management Center)"
          :legal-basis "VDA Band 2 -- Produktionsprozess- und Produktfreigabe (PPF) / IATF 16949:2016 (Referenz; Branchenstandard, kein Gesetz)"
          :national-spec "VDA 2 PPF-Stufen 1-6 (deutsches PPAP-Äquivalent)"
          :provenance "https://vda-qmc.de/"
          :required-evidence ["Prozessfähigkeitsnachweis (Cpk/Ppk) (initial-process-studies-report)"
                              "Messsystemanalyse (MSA) (measurement-system-analysis-report)"
                              "Teilefreigabebericht (part-submission-warrant/PPF-Bericht)"
                              "Prüfplan (control-plan)"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "SMMT (Society of Motor Manufacturers and Traders)"
          :legal-basis "IATF 16949:2016 (SMMT is one of the five IATF sponsor associations) / AIAG PPAP Manual (reference; industry-driven, not UK statute)"
          :national-spec "SMMT/IATF-16949 accreditation route to OEM PPAP submission"
          :provenance "https://www.smmt.co.uk/"
          :required-evidence ["Initial process studies (Cpk/Ppk) report"
                              "Measurement System Analysis (MSA) report"
                              "Part Submission Warrant (PSW)"
                              "Control plan"]}
   "JPN" {:name "Japan"
          :owner-authority "日本自動車部品工業会 (JAPIA) / JASO (自動車技術会規格)"
          :legal-basis "IATF 16949 (自動車産業品質マネジメントシステム規格の採用) / JASO規格 (参考。法定義務ではなく顧客/業界要求)"
          :national-spec "JAPIA品質保証ガイドライン準拠のPPAP相当プロセス"
          :provenance "https://www.japia.or.jp/"
          :required-evidence ["工程能力調査報告書 (initial-process-studies-cpk-report)"
                              "測定システム分析報告書 (measurement-system-analysis-report)"
                              "部品submission保証書 (part-submission-warrant/PSW)"
                              "検査基準書 (control-plan)"]}})

(defn spec-basis [iso3] (get catalog iso3))

(defn coverage
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-2930 R0: " (count catalog)
                 " jurisdictions seeded. Extend `autoparts.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))
