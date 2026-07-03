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

	s := &Store{DB: db}
	return s, nil
}

// Close closes the database connection.
func (s *Store) Close() error {
	return s.DB.Close()
}
