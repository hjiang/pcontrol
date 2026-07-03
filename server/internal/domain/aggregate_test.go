package domain

import "testing"

// --- CountedTotalSeconds ---

func TestCountedTotal_NoExclusions(t *testing.T) {
	apps := []UsageTotal{{KindApp, "com.game", "Game", 600}}
	web := []UsageTotal{{KindWeb, "youtube.com", "YouTube", 300}}
	total := CountedTotalSeconds(apps, web, nil)
	// 600 + 300 = 900
	if total != 900 {
		t.Errorf("expected 900, got %d", total)
	}
}

func TestCountedTotal_ExcludedAppSubtracted(t *testing.T) {
	apps := []UsageTotal{
		{KindApp, "com.game", "Game", 600},
		{KindApp, "com.duolingo", "Duolingo", 120},
	}
	web := []UsageTotal{{KindWeb, "youtube.com", "YouTube", 300}}
	exclusions := []Exclusion{{Kind: KindApp, Subject: "com.duolingo"}}
	// 600 + 300 + (120 - 120) = 900
	total := CountedTotalSeconds(apps, web, exclusions)
	if total != 900 {
		t.Errorf("expected 900, got %d", total)
	}
}

func TestCountedTotal_ExcludedDomainSubtracts(t *testing.T) {
	apps := []UsageTotal{{KindApp, "com.android.chrome", "Chrome", 600}}
	web := []UsageTotal{
		{KindWeb, "youtube.com", "YouTube", 120},
		{KindWeb, "khanacademy.org", "Khan", 60},
	}
	exclusions := []Exclusion{{Kind: KindWeb, Subject: "khanacademy.org"}}
	// 600 + 120 + (60 - 60) = 720
	total := CountedTotalSeconds(apps, web, exclusions)
	if total != 720 {
		t.Errorf("expected 720, got %d", total)
	}
}

func TestCountedTotal_ResultClampedAtZero(t *testing.T) {
	apps := []UsageTotal{{KindApp, "com.app", "App", 10}}
	web := []UsageTotal{{KindWeb, "excluded.com", "Excluded", 100}}
	exclusions := []Exclusion{{Kind: KindWeb, Subject: "excluded.com"}}
	// 10 + (100 - 100) = 10, not negative even though excluded > counted
	if total := CountedTotalSeconds(apps, web, exclusions); total != 10 {
		t.Errorf("expected 10, got %d", total)
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
	// counted: 100 (A) + 200 (B) + 300 (C) + 50 (site) = 650
	// excluded: 100 (A) + 300 (C) + 50 (site) = 450
	// total: 650 - 450 = 200
	total := CountedTotalSeconds(apps, web, exclusions)
	if total != 200 {
		t.Errorf("expected 200, got %d", total)
	}
}

func TestCountedTotal_Empty(t *testing.T) {
	if total := CountedTotalSeconds(nil, nil, nil); total != 0 {
		t.Errorf("expected 0, got %d", total)
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
