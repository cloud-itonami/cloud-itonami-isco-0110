(ns officer-admin.governor
  "OfficerAdminGovernor — the independent safety/traceability layer for
  the ISCO-08 0110 Commissioned Officer administrative assistant actor.
  Wired as its own `:govern` node in `officer-admin.actor`'s StateGraph,
  downstream of `:advise` — the Advisor has no notion of officer provenance
  or administrative risk, so this MUST be a separate system able to reject
  a proposal (itonami actor pattern, per ADR-2607011000 / CLAUDE.md Actors
  section).

  `check` is a pure function of (request, context, proposal, store) ->
  verdict; it never mutates the store. The StateGraph's `:decide` node
  routes on the verdict:
    :hard? true                → :hold  (irreversible, no write)
    :escalate? true            → :request-approval (interrupt-before)
    otherwise                  → :commit

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. officer provenance      — the request's officer must be registered.
    2. no-actuation            — proposal :effect must be :propose.
    3. scope-boundary          — proposals touching personnel deployment,
                                  command authority, weapons/targeting,
                                  classified operations, or lethal decisions
                                  NEVER PROCEED (closed allowlist enforced
                                  here + in advisor).

  ESCALATION invariants (:escalate? true, ALWAYS human sign-off):
    4. readiness-threshold     — readiness reports flagging below-threshold
                                  operational status.
    5. active-status-leave     — leave requests during active-status periods.
    6. low confidence          (< `confidence-floor`)."
  (:require [officer-admin.store :as store]))

(def confidence-floor 0.6)

; Permanently forbidden operation categories (out of scope entirely)
(def ^:private forbidden-ops #{:unknown})  ; :unknown catches out-of-scope proposals

; Escalating operations (require human approval)
(def ^:private escalating-ops #{:schedule-training
                                 :draft-correspondence})

(defn- hard-violations [{:keys [proposal]} officer-record]
  (cond-> []
    (nil? officer-record)
    (conj {:rule :no-officer :detail "officer not registered"})

    (not= :propose (:effect proposal))
    (conj {:rule :no-actuation :detail "effect must be :propose only (no direct store writes)"})

    (contains? forbidden-ops (:op proposal))
    (conj {:rule :scope-boundary
           :detail "operation outside permitted scope (personnel deployment, command authority, weapons/targeting, classified operations, lethal decisions are permanently forbidden)"})))

(defn- is-below-threshold? [readiness-status]
  ; Readiness levels: high, nominal, degraded, critical
  ; Below threshold means degraded or critical
  (or (= readiness-status :degraded)
      (= readiness-status :critical)))

(defn- is-active-status? [officer-record]
  ; Simplified: active-duty officers have certain restrictions on leave
  ; In real implementation, would check duty status, deployment calendar, etc.
  (get officer-record :active-duty false))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `officer-admin.store/Store`. Returns
  `{:ok? bool :violations [...] :confidence n :hard? bool :escalate? bool}`."
  [request context proposal store]
  (let [officer-record (store/officer store (:officer-id request))
        hard (hard-violations {:proposal proposal} officer-record)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        escalating-op? (contains? escalating-ops (:op proposal))

        ; Additional escalation checks based on operation type
        readiness-escalate? (and (= :log-readiness-report (:op proposal))
                                 (is-below-threshold? (:status proposal)))
        leave-escalate? (and (= :process-leave-request (:op proposal))
                            (is-active-status? officer-record))]
    {:ok? (and (not hard?) (not low?) (not escalating-op?)
               (not readiness-escalate?) (not leave-escalate?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? escalating-op?
                                      readiness-escalate? leave-escalate?))}))
