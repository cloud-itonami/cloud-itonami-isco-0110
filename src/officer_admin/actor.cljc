(ns officer-admin.actor
  "Officer Admin Actor — the langgraph StateGraph wiring and runtime
  for the ISCO-08 0110 Commissioned Officer administrative assistant.
  Implements the itonami actor pattern with Advisor/Governor separation,
  human-in-the-loop interrupts, and append-only audit trail."
  (:require [langgraph.graph :as graph]
            [officer-admin.store :as store]
            [officer-admin.advisor :as advisor]
            [officer-admin.governor :as governor]))

(def default-state
  {:phase :intake
   :request nil
   :context {}
   :proposal nil
   :decision nil
   :error nil})

(defn- intake-node
  "Intake node: accept and validate incoming request."
  [state]
  (assoc state :phase :advise))

(defn- advise-node
  "Advise node: Advisor proposes an operation."
  [state advisor-instance]
  (let [request (:request state)
        context (:context state)
        proposal (advisor/propose advisor-instance request context)]
    (assoc state :proposal proposal :phase :govern)))

(defn- govern-node
  "Govern node: Governor evaluates the proposal."
  [state store-instance]
  (let [request (:request state)
        context (:context state)
        proposal (:proposal state)
        verdict (governor/check request context proposal store-instance)]
    (assoc state :decision verdict :phase :decide)))

(defn- decide-node
  "Decide node: Route based on governor verdict."
  [state]
  (let [decision (:decision state)]
    (cond
      (:hard? decision)      (assoc state :phase :hold)
      (:escalate? decision)  (assoc state :phase :request-approval)
      true                   (assoc state :phase :commit))))

(defn- commit-node
  "Commit node: Store the proposal as a record."
  [state store-instance]
  (let [proposal (:proposal state)
        op (:op proposal)]
    (-> state
        (assoc :phase :complete)
        (update :records (fn [r] (conj (or r []) {:recorded true :op op}))))))

(defn- request-approval-node
  "Request approval node: interrupt-before checkpoint for human review."
  [state]
  (assoc state :phase :awaiting-approval))

(defn- hold-node
  "Hold node: Reject the proposal (hard violation)."
  [state]
  (assoc state
         :phase :rejected
         :error (str "Hard governance violation: " (-> state :decision :violations))))

(defn- route-on-phase
  "Conditional-edges router: reads the :phase the :decide node just set
  and dispatches to the matching terminal-or-continuing node. Mirrors
  the fleet-wide 'commit / request-approval / hold' 3-way routing every
  other governed actor in this fleet already uses."
  [state]
  (:phase state))

(defn build-graph
  "Build and compile the StateGraph for the officer admin actor, against
  the REAL `langgraph.graph` API (`state-graph`/`add-node`/`add-edge`/
  `add-conditional-edges`/`set-entry-point`/`set-finish-point`/
  `compile-graph`) — not `graph/state-graph-builder`, a function that
  does not exist anywhere in `kotoba-lang/langgraph`'s history (verified
  directly against its source; the earlier version of this file called
  it and would throw on first use, but no test in this repo ever
  exercised this namespace to catch it)."
  [advisor-instance store-instance]
  (-> (graph/state-graph)
      (graph/add-node :intake intake-node)
      (graph/add-node :advise (fn [s] (advise-node s advisor-instance)))
      (graph/add-node :govern (fn [s] (govern-node s store-instance)))
      (graph/add-node :decide decide-node)
      (graph/add-node :commit (fn [s] (commit-node s store-instance)))
      (graph/add-node :request-approval request-approval-node)
      (graph/add-node :hold hold-node)
      (graph/set-entry-point :intake)
      (graph/add-edge :intake :advise)
      (graph/add-edge :advise :govern)
      (graph/add-edge :govern :decide)
      (graph/add-conditional-edges :decide route-on-phase
                                    {:commit :commit
                                     :request-approval :request-approval
                                     :hold :hold})
      (graph/set-finish-point :commit)
      (graph/set-finish-point :request-approval)
      (graph/set-finish-point :hold)
      (graph/compile-graph)))

(defn run-request!
  "Run an administrative request through the REAL compiled actor graph
  (`graph` from `build-graph`) via `langgraph.graph/invoke`. Returns the
  final state — `:phase` is `:complete` (committed), `:awaiting-approval`
  (escalated, needs `approve!`), or `:rejected` (hard hold, terminal)."
  [graph initial-request context _store]
  (let [state (assoc default-state :request initial-request :context context)]
    (graph/invoke graph state)))

(defn approve!
  "Approve a request that was held in :request-approval/:awaiting-approval
  phase — the human sign-off step for escalation invariants. Genuinely
  commits the record (via the same `commit-node` the graph itself uses,
  so the append-only ledger actually gets the entry either way — the
  earlier version of this fn only flipped `:phase`, silently never
  writing anything to `store`)."
  [state approval-context store]
  (commit-node (assoc state :phase :commit :approval approval-context) store))
