(ns officer-admin.store
  "Officer Admin Store — the append-only audit ledger and persistent
  state for the ISCO-08 0110 Commissioned Officer administrative assistant
  actor. Implements the Store protocol for officer/unit identity verification
  and administrative record management.

  Two backends implement the same `Store` protocol so the backend is a
  swap, not a rewrite (the itonami actor pattern's injection boundary,
  ADR-2607011000):

    - `MemStore`     — a persistent (immutable) record wrapping plain
                       maps. The deterministic default for dev/tests/demo
                       (no deps); mutator methods return a NEW store.
    - `DatomicStore` — backed by `langchain.db`, a Datomic-API-compatible
                       EAV store (swappable to a kotoba-server pod in
                       production). Officer/unit records and ledger
                       entries carry free-form fields, so each is stored
                       as an EDN-blob payload via `langchain-store.core`
                       (`ls/enc`/`ls/dec*`), not a hand-rolled codec
                       (ADR-2607141600). Mutator methods return the SAME
                       store (the conn is already mutable), which is
                       still safe to `->`-thread the way the tests do.

  Both pass the same contract (test/officer_admin/store_contract_test.cljc)."
  (:require [langchain.db :as d]
            [langchain-store.core :as ls]))

(defprotocol Store
  "Store protocol for officer admin actor state and audit ledger."
  (officer [store officer-id]
    "Retrieve an officer record by ID. Returns nil if not found.")
  (unit [store unit-id]
    "Retrieve a unit record by ID. Returns nil if not found.")
  (register-officer! [store officer-id officer-data]
    "Register an officer (adds to store, returns updated store).")
  (register-unit! [store unit-id unit-data]
    "Register a unit (adds to store, returns updated store).")
  (add-record! [store record-type record-data]
    "Append an immutable administrative record to the audit ledger.")
  (records [store]
    "Return all records in the audit ledger (immutable)."))

(defrecord MemStore [officers units ledger]
  Store
  (officer [this officer-id]
    (get officers officer-id))
  (unit [this unit-id]
    (get units unit-id))
  (register-officer! [this officer-id officer-data]
    (MemStore. (assoc officers officer-id officer-data) units ledger))
  (register-unit! [this unit-id unit-data]
    (MemStore. officers (assoc units unit-id unit-data) ledger))
  (add-record! [this record-type record-data]
    (let [record (assoc record-data :type record-type :timestamp (System/currentTimeMillis))]
      (MemStore. officers units (conj ledger record))))
  (records [this]
    ledger))

(defn create-store
  "Create a new in-memory store for officer admin records."
  []
  (MemStore. {} {} []))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  (ls/identity-schema [:officer/id :unit/id :record/seq]))

(defn- blob-lookup
  "Look up the EDN-blob payload for the entity uniquely identified by
  `id-attr`/`id` and stored under `payload-attr`."
  [conn id-attr payload-attr id]
  (ls/dec* (d/q {:find '[?p .] :in '[$ ?id]
                 :where [['?e id-attr '?id] ['?e payload-attr '?p]]}
               (d/db conn) id)))

(defrecord DatomicStore [conn]
  Store
  (officer [_ officer-id]
    (blob-lookup conn :officer/id :officer/payload officer-id))
  (unit [_ unit-id]
    (blob-lookup conn :unit/id :unit/payload unit-id))
  (register-officer! [s officer-id officer-data]
    (d/transact! conn [{:officer/id officer-id :officer/payload (ls/enc officer-data)}])
    s)
  (register-unit! [s unit-id unit-data]
    (d/transact! conn [{:unit/id unit-id :unit/payload (ls/enc unit-data)}])
    s)
  (add-record! [s record-type record-data]
    (let [record (assoc record-data :type record-type :timestamp (System/currentTimeMillis))]
      (ls/append-blob! conn :record/seq :record/payload (count (records s)) record))
    s)
  (records [_]
    (ls/read-stream conn :record/seq :record/payload)))

(defn datomic-store
  "Create a new DatomicStore (langchain.db-backed) for officer admin
  records — the production-shaped backend for the same `Store` protocol
  `create-store`'s `MemStore` implements."
  []
  (->DatomicStore (d/create-conn schema)))
