(ns officer-admin.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [officer-admin.store :as store]))

(deftest test-create-store
  (testing "Create a new store"
    (let [s (store/create-store)]
      (is (not (nil? s)))
      (is (satisfies? store/Store s)))))

(deftest test-register-officer
  (testing "Register and retrieve an officer"
    (let [s (store/create-store)
          s' (store/register-officer! s "off-001" {:name "Captain Smith" :rank "O-3"})
          retrieved (store/officer s' "off-001")]
      (is (= (:name retrieved) "Captain Smith"))
      (is (= (:rank retrieved) "O-3")))))

(deftest test-register-unit
  (testing "Register and retrieve a unit"
    (let [s (store/create-store)
          s' (store/register-unit! s "unit-001" {:name "Alpha Company" :strength 100})
          retrieved (store/unit s' "unit-001")]
      (is (= (:name retrieved) "Alpha Company"))
      (is (= (:strength retrieved) 100)))))

(deftest test-add-record
  (testing "Add and retrieve records from audit ledger"
    (let [s (store/create-store)
          s' (store/add-record! s :readiness-report {:officer-id "off-001" :status "nominal"})
          records (store/records s')]
      (is (= (count records) 1))
      (is (= (:type (first records)) :readiness-report)))))

(deftest test-immutability
  (testing "Store operations return new store instances"
    (let [s (store/create-store)
          s' (store/register-officer! s "off-001" {:name "Captain Smith"})
          off-in-s (store/officer s "off-001")
          off-in-s' (store/officer s' "off-001")]
      (is (nil? off-in-s))
      (is (not (nil? off-in-s')))
      (is (= (:name off-in-s') "Captain Smith")))))
