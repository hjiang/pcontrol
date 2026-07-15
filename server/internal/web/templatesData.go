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
	Name           string
	TotalLimitText string
	WarnPct        int
	HasTotalLimit  bool
	TotalLimitMin  int
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

// --- Register device view data ---

type registerData struct {
	Success    bool
	DeviceName string
	Token      string
	DeviceID   int64
}

// PageTitler is implemented by view-model structs to customize the browser tab title.
type PageTitler interface {
	PageTitle() string
}

// MinimalLayouter is implemented by view-model structs that should suppress
// the nav bar and footer in the layout.
type MinimalLayouter interface {
	MinimalLayout() bool
}

func (dashboardData) PageTitle() string      { return "pcontrol — Devices" }
func (d deviceDetailData) PageTitle() string { return "pcontrol — " + d.Name }
func (d limitsData) PageTitle() string       { return "pcontrol — Limits · " + d.Name }
func (loginData) PageTitle() string          { return "pcontrol — Sign in" }
func (d registerData) PageTitle() string {
	if d.Success {
		return "pcontrol — Device Registered"
	}
	return "pcontrol — Register Device"
}
func (loginData) MinimalLayout() bool { return true }
