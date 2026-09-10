(ns officer-admin.store-contract-test
  "MemStore ≡ DatomicStore parity for the Store protocol — proves the
  backend swap (ADR-2607011000 injection boundary) is real: the same
  sequence of operations against either backend produces the same
  observable results."
  (:require [clojure.test :refer [deftest is testing]]
            [officer-admin.store :as store]))

(defn- exercise
  "Runs the same op sequence against `s` and reads back through
  whatever store the LAST op returned -- required because MemStore is
  immutable (each mutator returns a NEW store; the original binding
  never changes) while DatomicStore mutates its conn in place and
  returns the same store either way. Threading through `->` and
  reading from the final result observes the same committed state on
  both backends."
  [s]
  (let [s (-> s
              (store/register-officer! "off-001" {:name "Captain Smith" :rank "O-3"})
              (store/register-unit! "unit-001" {:name "Alpha Company" :strength 100})
              (store/add-record! :readiness-report {:officer-id "off-001" :status "nominal"}))]
    {:officer (store/officer s "off-001")
     :unit (store/unit s "unit-001")
     :records (store/records s)}))

(deftest mem-and-datomic-parity
  (testing "same operations against MemStore and DatomicStore observe the same results"
    (let [mem (exercise (store/create-store))
          dat (exercise (store/datomic-store))]
      (is (= "Captain Smith" (:name (:officer mem))))
      (is (= "Captain Smith" (:name (:officer dat))))
      (is (= "O-3" (:rank (:officer mem))) (= "O-3" (:rank (:officer dat))))
      (is (= "Alpha Company" (:name (:unit mem))))
      (is (= "Alpha Company" (:name (:unit dat))))
      (is (= 1 (count (:records mem))))
      (is (= 1 (count (:records dat))))
      (is (= :readiness-report (:type (first (:records mem)))))
      (is (= :readiness-report (:type (first (:records dat)))))
      (is (= "nominal" (:status (first (:records mem)))))
      (is (= "nominal" (:status (first (:records dat)))))
      (is (some? (:timestamp (first (:records mem)))))
      (is (some? (:timestamp (first (:records dat))))))))

(deftest datomic-store-nil-lookups
  (testing "unregistered officer/unit lookups are nil on the DatomicStore too"
    (let [dat (store/datomic-store)]
      (is (nil? (store/officer dat "no-such-officer")))
      (is (nil? (store/unit dat "no-such-unit"))))))
