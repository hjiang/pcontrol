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
func migrateV2(db *sql.DB) error {
	var n int
	err := db.QueryRow(
		`SELECT COUNT(*) FROM pragma_table_info('devices')
		 WHERE name = 'battery_percent'`).Scan(&n)
	if err != nil {
		return fmt.Errorf("check battery column: %w", err)
	}
	if n > 0 {
		return nil // already applied
	}
	stmts := []string{
		`ALTER TABLE devices ADD COLUMN battery_percent INTEGER`,
		`ALTER TABLE devices ADD COLUMN battery_charging INTEGER`,
		`ALTER TABLE devices ADD COLUMN battery_updated_at TEXT`,
	}
	for _, stmt := range stmts {
		if _, err := db.Exec(stmt); err != nil {
			return fmt.Errorf("apply v2 migration: %w", err)
		}
	}
	_, err = db.Exec(
		`INSERT OR IGNORE INTO schema_migrations (version) VALUES (2)`)
	return err
}

// Close closes the database connection.
func (s *Store) Close() error {
	return s.DB.Close()
}
