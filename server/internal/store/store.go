package store

import (
	"database/sql"
	"embed"
	"fmt"

	_ "modernc.org/sqlite"
)

//go:embed migrations.sql
var migrationsFS embed.FS

// Store wraps the SQLite database connection.
type Store struct {
	DB *sql.DB
}

// Open opens (or creates) the SQLite database at path and runs migrations.
func Open(path string) (*Store, error) {
	db, err := sql.Open("sqlite", path+"?_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)")
	if err != nil {
		return nil, fmt.Errorf("store open: %w", err)
	}

	// Apply migrations
	migrations, err := migrationsFS.ReadFile("migrations.sql")
	if err != nil {
		return nil, fmt.Errorf("store read migrations: %w", err)
	}
	if _, err := db.Exec(string(migrations)); err != nil {
		return nil, fmt.Errorf("store apply migrations: %w", err)
	}

	if err := migrateV2(db); err != nil {
		return nil, fmt.Errorf("store v2 migration: %w", err)
	}

	s := &Store{DB: db}
	return s, nil
}

// migrateV2 adds battery-status columns to devices (idempotent).
// Each column is checked and applied individually, so a mid-run failure
// only skips one column and the migration self-heals on the next startup.
func migrateV2(db *sql.DB) error {
	columnExists := func(name string) (bool, error) {
		var n int
		err := db.QueryRow(
			`SELECT COUNT(*) FROM pragma_table_info('devices') WHERE name = ?`, name).Scan(&n)
		if err != nil {
			return false, fmt.Errorf("check column %q: %w", name, err)
		}
		return n > 0, nil
	}

	type colDef struct {
		Name string
		SQL  string
	}
	columns := []colDef{
		{"battery_percent", `ALTER TABLE devices ADD COLUMN battery_percent INTEGER`},
		{"battery_charging", `ALTER TABLE devices ADD COLUMN battery_charging INTEGER`},
		{"battery_updated_at", `ALTER TABLE devices ADD COLUMN battery_updated_at TEXT`},
	}

	for _, c := range columns {
		exists, err := columnExists(c.Name)
		if err != nil {
			return err
		}
		if exists {
			continue
		}
		if _, err := db.Exec(c.SQL); err != nil {
			return fmt.Errorf("add column %q: %w", c.Name, err)
		}
	}

	_, err := db.Exec(
		`INSERT OR IGNORE INTO schema_migrations (version) VALUES (2)`)
	return err
}

// Close closes the database connection.
func (s *Store) Close() error {
	return s.DB.Close()
}
