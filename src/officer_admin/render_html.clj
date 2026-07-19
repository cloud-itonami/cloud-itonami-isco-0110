(ns officer-admin.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.
  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300)
  for the ISCO-08 cluster: this repo previously had NO demo page and no
  generator at all (the README's reference to
  `docs/samples/operator-console.html` as 'pure-data HTML output of
  kotoba.robotics.ui' was stale/aspirational -- no such file, generator,
  or `kotoba.robotics.ui` dependency existed anywhere in this repo before
  this commit; confirmed by a full tree listing before writing this file).

  This namespace drives the REAL actor stack (`officer-admin.actor` ->
  `officer-admin.governor` -> `officer-admin.store`) through a scenario
  built from real, exercised store data and renders the result
  deterministically -- no invented numbers, no timestamps in the page
  content, byte-identical across reruns against the same seed.

  Seed data disclosure (read directly from
  `test/officer_admin/actor_test.cljc`'s `fresh-store` fixture before
  writing this file):
    officer `off-001` / `{:name \"Test Officer\"}` is lifted VERBATIM from
    that fixture. Officer `off-002` / `{:name \"Officer Jordan Reyes\"
    :active-duty true}` is ADDITIONAL demo data registered here via the
    store's own real `register-officer!` protocol call -- disclosed
    plainly, not presented as if it were a pre-existing fixture. It exists
    solely to exercise the `active-status-leave` escalation invariant
    (`governor.cljc`'s `leave-escalate?`), which requires an officer whose
    record has `:active-duty true` -- `off-001` defaults to `false`
    (`governor.cljc`'s `is-active-status?` reads `(get officer-record
    :active-duty false)`), so a leave request from `off-001` auto-commits
    instead of escalating; this scenario includes BOTH requests side by
    side to demonstrate the contrast honestly with real code, not a
    hand-picked single case.

  Honest gaps found while reading the real source (disclosed, not hidden):
    - `officer-admin.governor/check`'s HARD rule #2 (`:no-actuation`,
      \"effect must be :propose\") is structurally UNREACHABLE through
      this repo's own advisors: both `mock-advisor` and the unimplemented
      `llm-advisor` placeholder always set `:effect :propose` on every
      proposal they construct, so no request driven through the real
      `advise-node` can ever produce a `:no-actuation` violation. This
      demo does not fabricate a path around that -- it is left out and
      named here instead, exactly as isco-1211/1111's render_html.clj
      disclosed their own unreachable rules.
    - Similarly, ESCALATION invariant #6 (\"low confidence\") can only be
      driven below `governor/confidence-floor` (0.6) via `mock-advisor`'s
      `:op :unknown` fallback (unrecognized request `:type`) -- but that
      same fallback is ALSO always a `:scope-boundary` HARD violation
      (`:unknown` is in `governor/forbidden-ops`), and `governor/check`'s
      own `:escalate?` formula is `(and (not hard?) (or low? ...))` --
      i.e. hard always wins. So there is no request this demo can send
      through the real advisor that reaches `:escalate?` via low
      confidence alone; every low-confidence path this repo's advisor can
      produce is hard-held instead. This scenario still exercises the
      `:scope-boundary` hard rule directly (via an unrecognized `:type`)
      to show that route, honestly labeled by its actual triggering rule.
    - `officer-admin.governor`'s docstring enumerates six numbered
      invariants but the actual `check` implementation has a SEVENTH,
      unnumbered escalation source: the `escalating-ops` set
      (`#{:schedule-training :draft-correspondence}`) -- any proposal
      with one of those two `:op`s escalates unconditionally, regardless
      of confidence or officer status. This scenario exercises both
      members of that set and labels them by their real triggering
      mechanism (`escalating-op`) rather than folding them into one of
      the six numbered invariants, since the docstring itself does not.
    - `officer-admin.actor/commit-node` accepts a `store-instance`
      parameter but never calls `officer-admin.store/add-record!` (or any
      other store-mutating fn) on it -- confirmed by reading the full
      function body and by `grep`ping `add-record!` across `src/`, whose
      only real call sites are inside `store.cljc` itself and
      `test/officer_admin/store_test.cljc` (which exercises the store
      protocol directly, not through the actor). So in this repo's
      current implementation, a committed record lives only in the run's
      returned state (`:records` key) -- it is never persisted into the
      store's own append-only ledger via the actor graph. This page
      displays exactly that real returned state and does not claim
      ledger persistence that the code does not perform.

  Usage: `clojure -M:render-html [out-file]` (default
  `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [officer-admin.store :as store]
            [officer-admin.advisor :as advisor]
            [officer-admin.actor :as actor]))

(defn- run-op! [graph store* label request]
  (let [r1 (actor/run-request! graph request {} store*)]
    (case (:phase r1)
      :complete
      {:label label :request request :outcome :auto-committed
       :decision (:decision r1) :records (:records r1)}

      :awaiting-approval
      (let [r2 (actor/approve! r1 {:approver "cmdr-001"} store*)]
        {:label label :request request :outcome :approved-and-committed
         :decision (:decision r1) :records (:records r2)})

      :rejected
      {:label label :request request :outcome :hard-hold
       :decision (:decision r1) :error (:error r1)}

      {:label label :request request :outcome :unexpected-phase
       :phase (:phase r1) :decision (:decision r1)})))

(def ^:private op-specs
  [["off-001 / routine readiness (nominal)" "off-001"
    {:officer-id "off-001" :type :log-readiness :status :nominal :notes "all clear"}]
   ["off-001 / readiness below threshold (degraded)" "off-001"
    {:officer-id "off-001" :type :log-readiness :status :degraded :notes "maintenance backlog"}]
   ["off-001 / schedule training" "off-001"
    {:officer-id "off-001" :type :schedule-training
     :training-id "TRN-2026-014" :curriculum "leadership-refresher"}]
   ["off-001 / draft correspondence" "off-001"
    {:officer-id "off-001" :type :draft-correspondence
     :corr-type :memo :subject "unit reassignment notice (administrative filing only)"}]
   ["off-001 / leave request (not active-duty)" "off-001"
    {:officer-id "off-001" :type :process-leave
     :leave-type :annual :start-date "2026-08-01" :end-date "2026-08-10"}]
   ["off-002 / leave request (active-duty)" "off-002"
    {:officer-id "off-002" :type :process-leave
     :leave-type :emergency :start-date "2026-07-20" :end-date "2026-07-22"}]
   ["off-999 (unregistered) / routine readiness" "off-999"
    {:officer-id "off-999" :type :log-readiness :status :nominal}]
   ["off-001 / unrecognized request type" "off-001"
    {:officer-id "off-001" :type :bogus-request-type}]])

(defn run-demo! []
  (let [st (-> (store/create-store)
               (store/register-officer! "off-001" {:name "Test Officer"})
               (store/register-officer! "off-002" {:name "Officer Jordan Reyes"
                                                     :active-duty true}))
        adv (advisor/mock-advisor)
        graph (actor/build-graph adv st)
        runs (mapv (fn [[label officer-id request]]
                     (run-op! graph st label request))
                   op-specs)]
    {:store st :runs runs}))

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- rule-name [{:keys [decision]}]
  (let [rules (->> decision :violations (map :rule) (map name))]
    (if (seq rules) (str/join ", " rules) "-")))

(defn- outcome-cell [{:keys [outcome] :as run}]
  (case outcome
    :auto-committed "<span class=\"ok\">committed</span>"
    :approved-and-committed "<span class=\"ok\">approved &amp; committed</span>"
    :hard-hold (str "<span class=\"critical\">HARD hold &middot; " (esc (rule-name run)) "</span>")
    "<span class=\"muted\">unexpected</span>"))

(defn- entities-table [store*]
  (str
   "<table><thead><tr><th>officer-id</th><th>name</th><th>active-duty</th><th>source</th></tr></thead><tbody>"
   "<tr><td>off-001</td><td>" (esc (:name (store/officer store* "off-001"))) "</td>"
   "<td>" (esc (boolean (get (store/officer store* "off-001") :active-duty false))) "</td>"
   "<td>test fixture (verbatim)</td></tr>"
   "<tr><td>off-002</td><td>" (esc (:name (store/officer store* "off-002"))) "</td>"
   "<td>" (esc (boolean (get (store/officer store* "off-002") :active-duty false))) "</td>"
   "<td>demo addition (disclosed, real <code>register-officer!</code> call)</td></tr>"
   "</tbody></table>"))

(defn- gate-table []
  (str
   "<table><thead><tr><th>#</th><th>rule</th><th>class</th><th>exercised in this demo?</th></tr></thead><tbody>"
   "<tr><td>1</td><td><code>no-officer</code></td><td class=\"critical\">HARD</td><td>yes (off-999)</td></tr>"
   "<tr><td>2</td><td><code>no-actuation</code></td><td class=\"critical\">HARD</td>"
   "<td class=\"muted\">no &mdash; structurally unreachable via this repo's advisors (see docstring)</td></tr>"
   "<tr><td>3</td><td><code>scope-boundary</code></td><td class=\"critical\">HARD</td><td>yes (unrecognized request type)</td></tr>"
   "<tr><td>4</td><td><code>readiness-threshold</code></td><td class=\"warn\">ESCALATE</td><td>yes (degraded readiness)</td></tr>"
   "<tr><td>5</td><td><code>active-status-leave</code></td><td class=\"warn\">ESCALATE</td><td>yes (off-002, contrasted against off-001)</td></tr>"
   "<tr><td>6</td><td>low confidence (&lt; 0.6)</td><td class=\"warn\">ESCALATE</td>"
   "<td class=\"muted\">no &mdash; every low-confidence path this advisor can produce is also HARD (see docstring)</td></tr>"
   "<tr><td>7&dagger;</td><td><code>escalating-op</code> (<code>schedule-training</code> / <code>draft-correspondence</code>)</td>"
   "<td class=\"warn\">ESCALATE</td><td>yes (both ops)</td></tr>"
   "</tbody></table>"
   "<p class=\"muted\">&dagger; unnumbered in <code>governor.cljc</code>'s own docstring, but present in the real <code>check</code> implementation &mdash; disclosed in the render_html.clj docstring.</p>"))

(defn- runs-table [runs]
  (str
   "<table><thead><tr><th>scenario</th><th>request</th><th>outcome</th><th>confidence</th></tr></thead><tbody>"
   (str/join
    ""
    (for [{:keys [label request decision] :as run} runs]
      (str "<tr><td>" (esc label) "</td>"
           "<td><code>" (esc (pr-str (dissoc request :officer-id))) "</code></td>"
           "<td>" (outcome-cell run) "</td>"
           "<td>" (esc (:confidence decision)) "</td></tr>")))
   "</tbody></table>"))

(defn render [{:keys [store runs]}]
  (str
   "<!doctype html>\n<html><head><meta charset=\"utf-8\">"
   "<title>Officer Admin Operator Console — cloud-itonami-isco-0110</title>"
   "<style>"
   "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;margin:2rem;color:#1c1c1e;background:#fff}"
   "h1{font-size:1.4rem}h2{font-size:1.1rem;margin-top:2rem}"
   "table{border-collapse:collapse;width:100%;margin:0.5rem 0 1rem}"
   "th,td{border:1px solid #d0d0d5;padding:0.4rem 0.6rem;text-align:left;font-size:0.92rem}"
   "th{background:#f5f5f7}"
   "code{font-family:ui-monospace,Menlo,monospace;font-size:0.85rem}"
   ".ok{color:#1a7f37;font-weight:600}"
   ".warn{color:#9a6700;font-weight:600}"
   ".critical{color:#b91c1c;font-weight:600}"
   ".muted{color:#6e6e73}"
   ".banner{background:#f5f5f7;border-radius:8px;padding:0.8rem 1rem;margin-bottom:1.5rem;font-size:0.9rem}"
   "</style></head><body>"
   "<h1>Officer Admin Operator Console</h1>"
   "<div class=\"banner\">Build-time render of the REAL <code>officer-admin.actor</code> "
   "StateGraph (<code>officer-admin.governor</code> &rarr; <code>officer-admin.store</code>), "
   "generated deterministically by <code>officer_admin.render-html</code>. No invented data: "
   "seed officer <code>off-001</code> is verbatim from the repo's own test fixture; "
   "<code>off-002</code> is a disclosed demo addition via the store's real API. "
   "See the namespace docstring in <code>src/officer_admin/render_html.clj</code> for full "
   "disclosure of seed provenance and honestly-unreachable governor rules.</div>"
   "<h2>Registered entities (real store state)</h2>"
   (entities-table store)
   "<h2>Governance action gate (officer-admin.governor/check contract)</h2>"
   (gate-table)
   "<h2>Audit trail (this demo's scenario run through the real graph)</h2>"
   (runs-table runs)
   "</body></html>\n"))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        html (render result)]
    (spit out html)
    (println "wrote" out)))
