package domain

import "time"

// Kind distinguishes app vs web usage.
type Kind string

const (
	KindApp Kind = "app"
	KindWeb Kind = "web"
)

// Event is a single usage event uploaded by the device.
type Event struct {
	EventID         string // client-generated UUID (dedupe key)
	DeviceID        int64
	Kind            Kind
	Subject         string // package name or registrable domain
	Label           string // human-readable name (e.g. "YouTube")
	Day             string // "YYYY-MM-DD" (device-local)
	StartedAt       time.Time
	DurationSeconds int
}

// Limit is a per-subject daily limit.
type Limit struct {
	ID                int64
	DeviceID          int64
	Kind              Kind
	Subject           string
	DailyLimitMinutes int
}

// Exclusion exempts a subject from the total daily limit.
type Exclusion struct {
	ID       int64
	DeviceID int64
	Kind     Kind
	Subject  string
}

// Policy is the full set of limits and settings for a device.
type Policy struct {
	Version              int
	TotalDailyLimitMin   *int // nil = no total limit
	WarnThresholdPercent int  // default 90
	Limits               []Limit
	Exclusions           []Exclusion
}

// Device represents a registered Android device.
type Device struct {
	ID               int64
	Name             string
	TokenHash        string // hex(sha256(raw token))
	CreatedAt        time.Time
	LastSeenAt       *time.Time // nil until first sync
	BatteryPercent   *int       // nil until first battery report, 0–100
	BatteryCharging  *bool      // nil until first battery report
	BatteryUpdatedAt *time.Time // nil until first battery report
}

// UsageTotal is an aggregate of usage events for one (kind, subject) on a day.
type UsageTotal struct {
	Kind    Kind
	Subject string
	Label   string
	Seconds int
}
