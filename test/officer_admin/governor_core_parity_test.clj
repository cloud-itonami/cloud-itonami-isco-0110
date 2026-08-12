(ns officer-admin.governor-core-parity-test
  "The governor's verdict, in cljc and in .kotoba, over the whole product.

  This is a safety layer: the actor may not write past what the governor
  allows. Three of its inputs are permanent refusals -- an unregistered
  officer, an effect that is not `:propose`, and an operation outside the
  permitted scope. Four more are grounds for human sign-off.

  Two properties carry the containment and each is one comparison from being
  lost, so both are asserted directly rather than only through agreement:

  - a hard violation is never an escalation. There is nothing to escalate to;
    a human cannot approve what the scope boundary forbids.
  - `ok?` and `escalate?` are mutually exclusive, and neither holds under a
    hard violation.

  2^6 flag combinations x four confidences = 256 rows, exhausted. A verdict
  table is exactly the shape where sampling checks the rows someone already
  thought of."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [officer-admin.governor :as governor]
            [officer-admin.store :as store]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private source (slurp "src/officer_admin/governor_core.kotoba"))

(def ^:private export-prefix
  (str "verdict-hold verdict-escalate verdict-ok confidence-floor-milli "
       "hard? low-confidence? escalation-ground? escalate? ok? verdict main"))

(def ^:private assessment-ty
  (str "[:record :governor/assessment [[:officer-registered :bool] "
       "[:effect-is-propose :bool] [:op-forbidden :bool] "
       "[:confidence-milli :i64] [:op-escalating :bool] "
       "[:readiness-below-threshold :bool] [:leave-during-active-status :bool]]]"))

(defn- run-probes [probes result-type]
  (let [defs (for [[name body] probes]
               (str "(defn " name " [] " result-type " " body ")"))
        src (-> source
                (str/replace-first
                 #"\(:export \[[^\]]+\]\)"
                 (str "(:export [" export-prefix " "
                      (str/join " " (map first probes)) "])"))
                (str "\n" (str/join "\n" defs)))
        {:keys [kir]} (compiler/compile-source src :wasm32-kotoba-v1 {})]
    (into {} (map (fn [[n _]] [n (ir/execute kir (symbol n) [])]) probes))))

;; ── driving the cljc through its real door ──────────────────────────────────
;;
;; `check` takes a Store and reads the officer record from it, so the fixture
;; is a Store and not a map. `forbidden-ops` is `#{:unknown}` and
;; `escalating-ops` is `#{:schedule-training :draft-correspondence}`; the rows
;; below pick ops that land on each side rather than restating the sets.

(defn- store-with [officer]
  (reify store/Store
    (officer [_ _] officer)
    (unit [_ _] nil)))

(defn- cljc-check [{:keys [registered propose forbidden confidence escalating
                          readiness leave]}]
  (let [officer (when registered {:id "o1" :active-duty (boolean leave)})
        op (cond forbidden :unknown
                 escalating :schedule-training
                 readiness :log-readiness-report
                 leave :process-leave-request
                 :else :file-report)
        proposal (cond-> {:op op
                          :effect (if propose :propose :commit)
                          :confidence confidence}
                   readiness (assoc :status :degraded))]
    (governor/check {:officer-id "o1"} {} proposal (store-with officer))))

(def ^:private rows
  (for [registered [true false] propose [true false] forbidden [true false]
        escalating [true false] readiness [true false] leave [true false]
        confidence [0.0 0.59 0.6 1.0]]
    {:registered registered :propose propose :forbidden forbidden
     :escalating escalating :readiness readiness :leave leave
     :confidence confidence}))

(defn- literal [{:keys [registered propose forbidden confidence escalating
                       readiness leave]}]
  (str "(record-new " assessment-ty " " registered " " propose " " forbidden " "
       (long (Math/round (* 1000.0 confidence))) " " escalating " "
       readiness " " leave ")"))

(deftest the-floor-agrees
  (is (= 600 (get (run-probes {"f" "(confidence-floor-milli)"} ":i64") "f")))
  (is (= 0.6 governor/confidence-floor)))

(deftest the-verdict-agrees-over-the-whole-product
  (is (= 256 (count rows)))
  (doseq [batch (partition-all 64 (map-indexed vector rows))]
    (let [probes (into {} (mapcat (fn [[i r]]
                                    [[(str "h" i) (str "(hard? " (literal r) ")")]
                                     [(str "e" i) (str "(escalate? " (literal r) ")")]
                                     [(str "o" i) (str "(ok? " (literal r) ")")]])
                                  batch))
          bools (run-probes probes ":bool")]
      (doseq [[i r] batch]
        (testing (pr-str r)
          (let [expected (cljc-check r)]
            (is (= (:hard? expected) (get bools (str "h" i))))
            (is (= (:escalate? expected) (get bools (str "e" i))))
            (is (= (:ok? expected) (get bools (str "o" i))))))))))

(deftest a-hard-violation-is-never-an-escalation
  ;; The containment property, stated rather than inferred. No combination of
  ;; the escalation grounds turns a refusal into a request for sign-off.
  (let [hard-rows (filter #(or (not (:registered %)) (not (:propose %)) (:forbidden %)) rows)]
    (is (pos? (count hard-rows)))
    (doseq [batch (partition-all 64 (map-indexed vector hard-rows))]
      (let [probes (into {} (mapcat (fn [[i r]]
                                      [[(str "e" i) (str "(escalate? " (literal r) ")")]
                                       [(str "o" i) (str "(ok? " (literal r) ")")]])
                                    batch))
            bools (run-probes probes ":bool")]
        (doseq [[i r] batch]
          (is (false? (get bools (str "e" i)))
              (str "hard must not escalate: " (pr-str r)))
          (is (false? (get bools (str "o" i)))
              (str "hard must not be ok: " (pr-str r))))))))

(deftest the-verdict-code-partitions-the-three-outcomes
  (let [probes (into {} (map-indexed (fn [i r] [(str "v" i) (str "(verdict " (literal r) ")")])
                                     rows))
        codes (run-probes probes ":i64")]
    (is (= #{0 1 2} (set (vals codes))) "the product reaches all three verdicts")
    (doseq [[i r] (map-indexed vector rows)]
      (let [e (cljc-check r)
            expected (cond (:hard? e) 0 (:escalate? e) 1 :else 2)]
        (is (= expected (get codes (str "v" i))) (pr-str r))))))

(deftest the-core-compiles-for-every-target-it-claims
  (doseq [target [:wasm32-kotoba-v1 :js-kotoba-v1 :x86_64-kotoba-v1 :aarch64-kotoba-v1]]
    (testing (name target)
      (is (some? (compiler/compile-source source target {}))))))
