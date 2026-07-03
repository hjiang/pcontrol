package domain

import "testing"

// --- CountedTotalSeconds ---

func TestCountedTotal_NoExclusions(t *testing.T) {
	apps := []UsageTotal{{KindApp, "com.game", "Game", 600}}
	web := []UsageTotal{{KindWeb, "youtube.com", "YouTube", 300}}
	total := CountedTotalSeconds(apps, web, nil)
	// §6: web time is a SUBSET of app time, so only non-excluded app counts;
	// non-excluded web is neither added nor subtracted.
	// countedTotal = 600 (app) = 600
	if total != 600 {
		t.Errorf("expected 600, got %d", total)
	}
}

func TestCountedTotal_ExcludedAppSubtracted(t *testing.T) {
	apps := []UsageTotal{
		{KindApp, "com.game", "Game", 600},
		{KindApp, "com.duolingo", "Duolingo", 120},
	}
	web := []UsageTotal{{KindWeb, "youtube.com", "YouTube", 300}}
	exclusions := []Exclusion{{Kind: KindApp, Subject: "com.duolingo"}}
	// §6: countedTotal = Σ non-excluded app − Σ excluded web
	// = 600 (game) − 0 (no excluded web) = 600
	// duolingo excluded app, youtube non-excluded web (subset, not added)
	total := CountedTotalSeconds(apps, web, exclusions)
	if total != 600 {
		t.Errorf("expected 600, got %d", total)
	}
}

func TestCountedTotal_ExcludedDomainSubtracts(t *testing.T) {
	apps := []UsageTotal{{KindApp, "com.android.chrome", "Chrome", 600}}
	web := []UsageTotal{
		{KindWeb, "youtube.com", "YouTube", 120},
		{KindWeb, "khanacademy.org", "Khan", 60},
	}
	exclusions := []Exclusion{{Kind: KindWeb, Subject: "khanacademy.org"}}
	// §6: countedTotal = Σ non-excluded app − Σ excluded web
	// = 600 (chrome) − 60 (khan) = 540
	// youtube is non-excluded web → subset of chrome time, not added
	total := CountedTotalSeconds(apps, web, exclusions)
	if total != 540 {
		t.Errorf("expected 540, got %d", total)
	}
}

func TestCountedTotal_ResultClampedAtZero(t *testing.T) {
	apps := []UsageTotal{{KindApp, "com.app", "App", 10}}
	web := []UsageTotal{{KindWeb, "excluded.com", "Excluded", 100}}
	exclusions := []Exclusion{{Kind: KindWeb, Subject: "excluded.com"}}
	// §6: countedTotal = 10 (app) − 100 (excluded web) = −90 → clamped to 0
	if total := CountedTotalSeconds(apps, web, exclusions); total != 0 {
		t.Errorf("expected 0 (clamped), got %d", total)
	}
}

func TestCountedTotal_MultipleExclusions(t *testing.T) {
	apps := []UsageTotal{
		{KindApp, "com.a", "A", 100},
		{KindApp, "com.b", "B", 200},
		{KindApp, "com.c", "C", 300},
	}
	web := []UsageTotal{{KindWeb, "site.com", "Site", 50}}
	exclusions := []Exclusion{
		{Kind: KindApp, Subject: "com.a"},
		{Kind: KindApp, Subject: "com.c"},
		{Kind: KindWeb, Subject: "site.com"},
	}
	// §6: countedTotal = Σ non-excluded app − Σ excluded web
	// = 200 (B) − 50 (site, excluded web) = 150
	total := CountedTotalSeconds(apps, web, exclusions)
	if total != 150 {
		t.Errorf("expected 150, got %d", total)
	}
}

func TestCountedTotal_Empty(t *testing.T) {
	if total := CountedTotalSeconds(nil, nil, nil); total != 0 {
		t.Errorf("expected 0, got %d", total)
	}
}

// §6: exclusions match by suffix on dot boundaries, so an exclusion on
// "khanacademy.org" must also subtract a "www.khanacademy.org" web total.
func TestCountedTotal_ExclusionSuffixMatches(t *testing.T) {
	apps := []UsageTotal{{KindApp, "com.android.chrome", "Chrome", 600}}
	web := []UsageTotal{
		{KindWeb, "www.khanacademy.org", "Khan", 90},
	}
	exclusions := []Exclusion{{Kind: KindWeb, Subject: "khanacademy.org"}}
	// countedTotal = 600 (chrome) − 90 (www.khanacademy.org matched by suffix)
	total := CountedTotalSeconds(apps, web, exclusions)
	if total != 510 {
		t.Errorf("expected 510, got %d", total)
	}
}

// --- MatchesDomain ---

func TestMatchesDomain_Exact(t *testing.T) {
	if !MatchesDomain("youtube.com", "youtube.com") {
		t.Error("expected exact match")
	}
}

func TestMatchesDomain_Subdomain(t *testing.T) {
	if !MatchesDomain("youtube.com", "m.youtube.com") {
		t.Error("expected subdomain match")
	}
}

func TestMatchesDomain_MultiLevelSubdomain(t *testing.T) {
	if !MatchesDomain("youtube.com", "music.youtube.com") {
		t.Error("expected multi-level subdomain match")
	}
}

func TestMatchesDomain_NoMatch(t *testing.T) {
	if MatchesDomain("youtube.com", "notyoutube.com") {
		t.Error("expected no match for unrelated domain")
	}
}

func TestMatchesDomain_SubstringNoMatch(t *testing.T) {
	// "notyoutube.com" ends with "youtube.com" but not on a dot boundary
	if MatchesDomain("youtube.com", "notyoutube.com") {
		t.Error("expected no match for substring without dot boundary")
	}
}

func TestMatchesDomain_ExactEqual(t *testing.T) {
	if !MatchesDomain("google.com", "google.com") {
		t.Error("expected exact match for google.com")
	}
}

func TestMatchesDomain_CaseSensitive(t *testing.T) {
	// domains are case-insensitive per spec
	if !MatchesDomain("YouTube.com", "youtube.com") {
		t.Error("domain matching should be case-insensitive")
	}
}
