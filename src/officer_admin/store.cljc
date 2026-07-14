(ns officer-admin.store
  "Officer Admin Store — the append-only audit ledger and persistent
  state for the ISCO-08 0110 Commissioned Officer administrative assistant
  actor. Implements the Store protocol for officer/unit identity verification
  and administrative record management.")

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
