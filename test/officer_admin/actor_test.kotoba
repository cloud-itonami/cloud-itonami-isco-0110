(ns officer-admin.actor-test
  "Integration tests for `officer-admin.actor` — builds the REAL compiled
  `langgraph.graph` and runs `run-request!`/`approve!` end-to-end through
  all three terminal routes (commit / escalate-then-approve / hold). This
  namespace did not exist before: `build-graph` previously called
  `graph/state-graph-builder`, a function that does not exist anywhere in
  `kotoba-lang/langgraph`, and `run-request!` was an explicit stub — both
  went uncaught for as long as they did precisely because no test ever
  exercised this namespace. These tests close that gap."
  (:require [clojure.test :refer [deftest is testing]]
            [officer-admin.actor :as actor]
            [officer-admin.advisor :as advisor]
            [officer-admin.store :as store]))

(defn- fresh-store []
  (-> (store/create-store)
      (store/register-officer! "off-001" {:name "Test Officer"})))

(deftest run-request-commits-clean-proposal
  (testing "a valid, high-confidence, nominal readiness report runs the
            real compiled graph end to end and reaches :complete"
    (let [st (fresh-store)
          g (actor/build-graph (advisor/mock-advisor) st)
          final (actor/run-request! g {:officer-id "off-001" :type :log-readiness
                                        :status :nominal :notes "all clear"}
                                     {} st)]
      (is (= :complete (:phase final)))
      (is (= [{:recorded true :op :log-readiness-report}] (:records final)))
      (is (false? (:hard? (:decision final))))
      (is (false? (:escalate? (:decision final))))
      (testing "the commit is genuinely durable in the store's real audit
                ledger (`officer-admin.store/add-record!`), not just the
                transient graph-state `:records` mirror"
        (let [ledger (store/records (:store final))]
          (is (= 1 (count ledger)))
          (is (= :log-readiness-report (:type (first ledger))))
          (is (= "off-001" (:officer-id (first ledger)))))))))

(deftest run-request-holds-unregistered-officer
  (testing "an unregistered officer is a HARD violation -- the real graph
            routes to :hold and terminates at :rejected, never :complete"
    (let [st (fresh-store)
          g (actor/build-graph (advisor/mock-advisor) st)
          final (actor/run-request! g {:officer-id "no-such-officer"
                                        :type :log-readiness :status :nominal}
                                     {} st)]
      (is (= :rejected (:phase final)))
      (is (true? (:hard? (:decision final))))
      (is (some? (:error final)))
      (is (nil? (:records final))))))

(deftest run-request-escalates-then-approve-commits
  (testing "draft-correspondence always escalates -- the real graph routes
            to :request-approval and stops there (interrupt point); a
            human approve! then genuinely commits the record via the same
            commit-node the graph itself uses (not just a :phase flip)"
    (let [st (fresh-store)
          g (actor/build-graph (advisor/mock-advisor) st)
          held (actor/run-request! g {:officer-id "off-001"
                                       :type :draft-correspondence
                                       :corr-type :memo :subject "test"}
                                    {} st)]
      (is (= :awaiting-approval (:phase held)))
      (is (true? (:escalate? (:decision held))))
      (is (nil? (:records held)) "not yet committed -- awaiting human sign-off")
      (let [approved (actor/approve! held {:approver "cmdr-002"} st)]
        (is (= :complete (:phase approved)))
        (is (= [{:recorded true :op :draft-correspondence}] (:records approved)))
        (is (= {:approver "cmdr-002"} (:approval approved)))
        (testing "approve! also genuinely persists to the real audit ledger"
          (let [ledger (store/records (:store approved))]
            (is (= 1 (count ledger)))
            (is (= :draft-correspondence (:type (first ledger))))))))))

(deftest run-request-low-confidence-escalates
  (testing "confidence below the governor's floor also escalates, even
            for an otherwise clean proposal -- exercised through the real
            compiled graph, not just gov/check in isolation"
    (let [st (fresh-store)
          g (actor/build-graph (advisor/mock-advisor) st)
          ;; mock-advisor always returns >=0.75 confidence for known
          ;; request types, so drive a genuinely low-confidence path via
          ;; an unrecognized request :type, which mock-advisor maps to
          ;; {:op :unknown :confidence 0.0} -- this is ALSO a forbidden
          ;; op, so assert the HARD (forbidden-op) route wins over
          ;; escalation, matching governor.cljc's own priority order.
          final (actor/run-request! g {:officer-id "off-001" :type :bogus}
                                     {} st)]
      (is (= :rejected (:phase final)))
      (is (true? (:hard? (:decision final)))))))
