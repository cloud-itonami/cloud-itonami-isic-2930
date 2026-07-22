(ns autoparts.facts-test
  (:require [clojure.test :refer [deftest is]]
            [autoparts.facts :as facts]))

(deftest jpn-has-a-spec-basis
  (is (some? (facts/spec-basis "JPN")))
  (is (string? (:provenance (facts/spec-basis "JPN")))))

(deftest unknown-jurisdiction-has-no-fabricated-spec-basis
  (is (nil? (facts/spec-basis "ATL"))))

(deftest coverage-never-reports-a-missing-jurisdiction-as-covered
  (let [report (facts/coverage ["JPN" "ATL" "USA"])]
    (is (= 2 (:covered report)))
    (is (= ["ATL"] (:missing-jurisdictions report)))
    (is (= ["JPN" "USA"] (:covered-jurisdictions report)))))

(deftest required-evidence-satisfied-needs-every-item
  (let [all (facts/evidence-checklist "JPN")]
    (is (facts/required-evidence-satisfied? "JPN" all))
    (is (not (facts/required-evidence-satisfied? "JPN" (rest all))))
    (is (not (facts/required-evidence-satisfied? "ATL" all)) "no spec-basis -> never satisfied")))

(deftest kor-has-a-spec-basis
  (is (some? (facts/spec-basis "KOR")))
  (is (string? (:provenance (facts/spec-basis "KOR")))))

(deftest kor-entry-has-the-same-map-shape-as-the-other-jurisdictions
  (is (= (set (keys (facts/spec-basis "JPN")))
         (set (keys (facts/spec-basis "KOR")))))
  (is (= 4 (count (facts/evidence-checklist "KOR")))))

(deftest kor-required-evidence-satisfied-needs-every-item
  (let [all (facts/evidence-checklist "KOR")]
    (is (facts/required-evidence-satisfied? "KOR" all))
    (is (not (facts/required-evidence-satisfied? "KOR" (rest all))))))

(deftest coverage-includes-kor-as-a-5th-covered-jurisdiction
  (let [report (facts/coverage ["JPN" "KOR" "ATL" "USA"])]
    (is (= 3 (:covered report)))
    (is (= ["ATL"] (:missing-jurisdictions report)))
    (is (= ["JPN" "KOR" "USA"] (:covered-jurisdictions report)))))
