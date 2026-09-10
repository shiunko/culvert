(ns culvert.persistence.schema)

(def migrations
  [{:version 1
    :name "initial-sqlite-schema"
    :statements
    ["CREATE TABLE IF NOT EXISTS users (username TEXT PRIMARY KEY COLLATE NOCASE, password_hash TEXT NOT NULL, role TEXT NOT NULL CHECK (role IN ('admin', 'user')), auth_version INTEGER NOT NULL DEFAULT 1 CHECK (auth_version > 0), quotas_json TEXT NOT NULL DEFAULT '{}', created_at TEXT NOT NULL)"
     "CREATE TABLE IF NOT EXISTS sessions (id TEXT PRIMARY KEY, username TEXT NOT NULL COLLATE NOCASE REFERENCES users(username) ON DELETE CASCADE, auth_version INTEGER NOT NULL CHECK (auth_version > 0), expires_at INTEGER NOT NULL, created_at TEXT NOT NULL)"
     "CREATE INDEX IF NOT EXISTS sessions_username_idx ON sessions(username)"
     "CREATE INDEX IF NOT EXISTS sessions_expires_at_idx ON sessions(expires_at)"
     "CREATE TABLE IF NOT EXISTS resources (id TEXT PRIMARY KEY, owner TEXT NOT NULL COLLATE NOCASE REFERENCES users(username) ON DELETE RESTRICT, resource_type TEXT NOT NULL, resource_key TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'requested', data_json TEXT NOT NULL DEFAULT '{}', created_at TEXT NOT NULL, updated_at TEXT NOT NULL, UNIQUE(resource_type, resource_key))"
     "CREATE INDEX IF NOT EXISTS resources_owner_type_idx ON resources(owner, resource_type)"
     "CREATE INDEX IF NOT EXISTS resources_state_idx ON resources(state)"
     "CREATE TABLE IF NOT EXISTS port_reservations (id TEXT PRIMARY KEY, owner TEXT NOT NULL COLLATE NOCASE REFERENCES users(username) ON DELETE CASCADE, resource_id TEXT REFERENCES resources(id) ON DELETE SET NULL, protocol TEXT NOT NULL CHECK (protocol IN ('tcp', 'udp')), port INTEGER NOT NULL CHECK (port BETWEEN 1 AND 65535), status TEXT NOT NULL DEFAULT 'reserved' CHECK (status IN ('reserved', 'committed', 'released', 'expired')), expires_at INTEGER, created_at TEXT NOT NULL, UNIQUE(protocol, port))"
     "CREATE INDEX IF NOT EXISTS port_reservations_owner_status_idx ON port_reservations(owner, status)"
     "CREATE INDEX IF NOT EXISTS port_reservations_expires_at_idx ON port_reservations(expires_at)"
     "CREATE TABLE IF NOT EXISTS proxy_rules (id TEXT PRIMARY KEY, owner TEXT NOT NULL COLLATE NOCASE REFERENCES users(username) ON DELETE RESTRICT, resource_id TEXT REFERENCES resources(id) ON DELETE CASCADE, domain TEXT NOT NULL COLLATE NOCASE, worker TEXT NOT NULL COLLATE NOCASE, target TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)), data_json TEXT NOT NULL DEFAULT '{}', created_at TEXT NOT NULL, updated_at TEXT NOT NULL, UNIQUE(domain, worker))"
     "CREATE INDEX IF NOT EXISTS proxy_rules_owner_idx ON proxy_rules(owner)"
     "CREATE TABLE IF NOT EXISTS resource_events (id INTEGER PRIMARY KEY AUTOINCREMENT, resource_id TEXT NOT NULL REFERENCES resources(id) ON DELETE CASCADE, event_type TEXT NOT NULL, from_state TEXT, to_state TEXT, data_json TEXT NOT NULL DEFAULT '{}', created_at TEXT NOT NULL)"
     "CREATE INDEX IF NOT EXISTS resource_events_resource_created_idx ON resource_events(resource_id, created_at)"
     "CREATE INDEX IF NOT EXISTS resource_events_type_idx ON resource_events(event_type)"]}
   {:version 2
    :name "add-port-reservation-bind-address"
    :statements
    ["ALTER TABLE port_reservations RENAME TO port_reservations_v1"
     "CREATE TABLE port_reservations (id TEXT PRIMARY KEY, owner TEXT NOT NULL COLLATE NOCASE REFERENCES users(username) ON DELETE CASCADE, resource_id TEXT REFERENCES resources(id) ON DELETE SET NULL, bind_address TEXT NOT NULL, protocol TEXT NOT NULL CHECK (protocol IN ('tcp', 'udp')), port INTEGER NOT NULL CHECK (port BETWEEN 1 AND 65535), status TEXT NOT NULL DEFAULT 'reserved' CHECK (status IN ('reserved', 'committed')), expires_at INTEGER, created_at TEXT NOT NULL, UNIQUE(bind_address, protocol, port))"
     "INSERT INTO port_reservations(id, owner, resource_id, bind_address, protocol, port, status, expires_at, created_at) SELECT id, owner, resource_id, '0.0.0.0', protocol, port, status, expires_at, created_at FROM port_reservations_v1 WHERE status IN ('reserved', 'committed')"
     "DROP TABLE port_reservations_v1"
     "CREATE INDEX port_reservations_owner_status_idx ON port_reservations(owner, status)"
     "CREATE INDEX port_reservations_expires_at_idx ON port_reservations(expires_at)"]}])
