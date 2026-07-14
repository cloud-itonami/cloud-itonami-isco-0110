(ns officer-admin.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [officer-admin.governor :as gov]
            [officer-admin.store :as store]))

(deftest test-hard-violations-unregistered-officer
  (testing "Unregistered officer is a hard violation"
    (let [test-store (store/create-store)
          request {:officer-id "unknown-officer"}
          context {}
          proposal {:op :schedule-training :effect :propose :confidence 0.9}
          verdict (gov/check request context proposal test-store)]
      (is (true? (:hard? verdict)))
      (is (false? (:ok? verdict))))))

(deftest test-hard-violations-wrong-effect
  (testing "Non-:propose effect is a hard violation"
    (let [test-store (-> (store/create-store)
                        (store/register-officer! "off-001" {:name "Test Officer"}))
          request {:officer-id "off-001"}
          context {}
          proposal {:op :schedule-training :effect :execute :confidence 0.9}
          verdict (gov/check request context proposal test-store)]
      (is (true? (:hard? verdict)))
      (is (seq (:violations verdict))))))

(deftest test-hard-violations-forbidden-op
  (testing "Forbidden operations (unknown/out-of-scope) are hard violations"
    (let [test-store (-> (store/create-store)
                        (store/register-officer! "off-001" {:name "Test Officer"}))
          request {:officer-id "off-001"}
          context {}
          proposal {:op :unknown :effect :propose :confidence 0.9}
          verdict (gov/check request context proposal test-store)]
      (is (true? (:hard? verdict)))
      (is (seq (:violations verdict))))))

(deftest test-valid-proposal
  (testing "Valid routine proposal from registered officer passes governor"
    (let [test-store (-> (store/create-store)
                        (store/register-officer! "off-001" {:name "Test Officer"}))
          request {:officer-id "off-001"}
          context {}
          proposal {:op :log-readiness-report :effect :propose :confidence 0.9 :status :nominal}
          verdict (gov/check request context proposal test-store)]
      (is (false? (:hard? verdict)))
      (is (true? (:ok? verdict))))))

(deftest test-low-confidence-escalation
  (testing "Low confidence (<0.6) triggers escalation"
    (let [test-store (-> (store/create-store)
                        (store/register-officer! "off-001" {:name "Test Officer"}))
          request {:officer-id "off-001"}
          context {}
          proposal {:op :log-readiness-report :effect :propose :confidence 0.4}
          verdict (gov/check request context proposal test-store)]
      (is (false? (:hard? verdict)))
      (is (true? (:escalate? verdict)))
      (is (false? (:ok? verdict))))))

(deftest test-readiness-below-threshold
  (testing "Readiness report below threshold triggers escalation"
    (let [test-store (-> (store/create-store)
                        (store/register-officer! "off-001" {:name "Test Officer"}))
          request {:officer-id "off-001"}
          context {}
          proposal {:op :log-readiness-report :effect :propose :confidence 0.9 :status :degraded}
          verdict (gov/check request context proposal test-store)]
      (is (true? (:escalate? verdict))))))

(deftest test-draft-correspondence-escalation
  (testing "Draft correspondence proposal requires escalation"
    (let [test-store (-> (store/create-store)
                        (store/register-officer! "off-001" {:name "Test Officer"}))
          request {:officer-id "off-001"}
          context {}
          proposal {:op :draft-correspondence :effect :propose :confidence 0.9}
          verdict (gov/check request context proposal test-store)]
      (is (true? (:escalate? verdict))))))
