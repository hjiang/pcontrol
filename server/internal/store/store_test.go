package store

import (
	"testing"
)

func TestMigrateV2_Idempotent(t *testing.T) {
	// Open a store, close it, open the same file again — no error
	path := t.TempDir() + "/test.db"

	s1, err := Open(path)
	if err != nil {
		t.Fatalf("first Open: %v", err)
	}
	s1.Close()

	s2, err := Open(path)
	if err != nil {
		t.Fatalf("second Open: %v", err)
	}
	defer s2.Close()

	// Verify the three new columns exist
	rows, err := s2.DB.Query(`SELECT name FROM pragma_table_info('devices')`)
	if err != nil {
		t.Fatalf("query pragma_table_info: %v", err)
	}
	defer rows.Close()

	cols := make(map[string]bool)
	for rows.Next() {
		var name string
		if err := rows.Scan(&name); err != nil {
			t.Fatalf("scan column: %v", err)
		}
		cols[name] = true
	}

	for _, want := range []string{"battery_percent", "battery_charging", "battery_updated_at"} {
		if !cols[want] {
			t.Errorf("missing column %q after second Open", want)
		}
	}
}
