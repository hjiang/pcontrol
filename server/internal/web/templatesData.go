package web

// --- Dashboard view data ---

type dashboardDeviceEntry struct {
	ID              int64
	Name            string
	LastSeenAt      string
	Online          bool
	LastSeenAge     string
	TotalMinutes    int
	HasLimit        bool
	LimitMin        int
	BarColor        string
	BarPercent      int
	HasBattery      bool
	BatteryPercent  int
	BatteryCharging bool
	BatteryLow      bool
	TopEntries      []topEntry
}

type topEntry struct {
	Label   string
	Minutes int
}

type dashboardData struct {
	Devices []dashboardDeviceEntry
}

// --- Device detail view data ---

type subjectRow struct {
	Label   string
	Minutes int
	Blocked bool
	Warn    bool
}

type historyRow struct {
	Day        string
	Minutes    int
	BarPercent int
}

type deviceDetailData struct {
	ID              int64
	Name            string
	Day             string
	TotalMinutes    int
	HasLimit        bool
	LimitMin        int
	WarnPct         int
	BarColor        string
	BarPercent      int
	BlockedBadge    bool
	WarnBadge       bool
	HasBattery      bool
	BatteryPercent  int
	BatteryCharging bool
	BatteryLow      bool
	History         []historyRow
	Apps            []subjectRow
	Websites        []subjectRow
}

// --- Limits page view data ---

type limitRow struct {
	ID                int64
	Kind              string
	Subject           string
	DailyLimitMinutes int
}

type exclusionRow struct {
	ID      int64
	Kind    string
	Subject string
}

type limitsData struct {
	ID             int64
	TotalLimitText string
	WarnPct        int
	Limits         []limitRow
	Exclusions     []exclusionRow
	Subjects       []subjectOption
}

type subjectOption struct {
	Value string
	Label string
}

// --- Login view data ---

type loginData struct {
	ErrorMsg string
}
