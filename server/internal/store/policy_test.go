package store

import (
	"testing"

	"pcontrol/server/internal/domain"
)

func TestSetLimit(t *testing.T) {
	s := newTestStore(t)
	dev, _ := mustCreateDevice(t, s, "phone-1")

	lim, err := s.SetLimit(dev.ID, domain.KindApp, "com.game", 30)
	if err != nil {
		t.Fatalf("SetLimit: %v", err)
	}
	if lim.DailyLimitMinutes != 30 {
		t.Errorf("expected 30 min, got %d", lim.DailyLimitMinutes)
	}
	if lim.ID == 0 {
		t.Error("expected non-zero limit ID")
	}
}

func TestSetLimit_UpdateExisting(t *testing.T) {
	s := newTestStore(t)
	dev, _ := mustCreateDevice(t, s, "phone-1")

	lim1, err := s.SetLimit(dev.ID, domain.KindApp, "com.game", 30)
	if err != nil {
		t.Fatalf("SetLimit first: %v", err)
	}

	lim2, err := s.SetLimit(dev.ID, domain.KindApp, "com.game", 45)
	if err != nil {
		t.Fatalf("SetLimit update: %v", err)
	}

	if lim2.ID != lim1.ID {
		t.Errorf("expected same limit ID %d, got %d", lim1.ID, lim2.ID)
	}
	if lim2.DailyLimitMinutes != 45 {
		t.Errorf("expected 45 min, got %d", lim2.DailyLimitMinutes)
	}
}

func TestDeleteLimit(t *testing.T) {
	s := newTestStore(t)
	dev, _ := mustCreateDevice(t, s, "phone-1")

	lim, err := s.SetLimit(dev.ID, domain.KindApp, "com.game", 30)
	if err != nil {
		t.Fatalf("SetLimit: %v", err)
	}

	if err := s.DeleteLimit(lim.ID); err != nil {
		t.Fatalf("DeleteLimit: %v", err)
	}

	policy, err := s.GetPolicy(dev.ID)
	if err != nil {
		t.Fatalf("GetPolicy: %v", err)
	}
	if len(policy.Limits) != 0 {
		t.Errorf("expected 0 limits after delete, got %d", len(policy.Limits))
	}
}

func TestAddDeleteExclusion(t *testing.T) {
	s := newTestStore(t)
	dev, _ := mustCreateDevice(t, s, "phone-1")

	exc, err := s.AddExclusion(dev.ID, domain.KindApp, "com.duolingo")
	if err != nil {
		t.Fatalf("AddExclusion: %v", err)
	}
	if exc.ID == 0 {
		t.Error("expected non-zero exclusion ID")
	}

	// Verify it appears in policy
	policy, err := s.GetPolicy(dev.ID)
	if err != nil {
		t.Fatalf("GetPolicy: %v", err)
	}
	if len(policy.Exclusions) != 1 {
		t.Fatalf("expected 1 exclusion, got %d", len(policy.Exclusions))
	}
	if policy.Exclusions[0].Subject != "com.duolingo" {
		t.Errorf("expected com.duolingo, got %s", policy.Exclusions[0].Subject)
	}

	// Delete it
	if err := s.DeleteExclusion(exc.ID); err != nil {
		t.Fatalf("DeleteExclusion: %v", err)
	}

	policy, err = s.GetPolicy(dev.ID)
	if err != nil {
		t.Fatalf("GetPolicy: %v", err)
	}
	if len(policy.Exclusions) != 0 {
		t.Errorf("expected 0 exclusions after delete, got %d", len(policy.Exclusions))
	}
}

func TestSetTotalLimitAndWarn(t *testing.T) {
	s := newTestStore(t)
	dev, _ := mustCreateDevice(t, s, "phone-1")

	total := 120
	if err := s.SetTotalLimit(dev.ID, &total); err != nil {
		t.Fatalf("SetTotalLimit: %v", err)
	}
	if err := s.SetWarnPercent(dev.ID, 80); err != nil {
		t.Fatalf("SetWarnPercent: %v", err)
	}

	policy, err := s.GetPolicy(dev.ID)
	if err != nil {
		t.Fatalf("GetPolicy: %v", err)
	}
	if policy.TotalDailyLimitMin == nil || *policy.TotalDailyLimitMin != 120 {
		t.Errorf("expected total limit 120, got %v", policy.TotalDailyLimitMin)
	}
	if policy.WarnThresholdPercent != 80 {
		t.Errorf("expected warn %d, got %d", 80, policy.WarnThresholdPercent)
	}
}

func TestClearTotalLimit(t *testing.T) {
	s := newTestStore(t)
	dev, _ := mustCreateDevice(t, s, "phone-1")

	total := 120
	if err := s.SetTotalLimit(dev.ID, &total); err != nil {
		t.Fatalf("SetTotalLimit: %v", err)
	}
	if err := s.SetTotalLimit(dev.ID, nil); err != nil {
		t.Fatalf("Clear total limit: %v", err)
	}

	policy, err := s.GetPolicy(dev.ID)
	if err != nil {
		t.Fatalf("GetPolicy: %v", err)
	}
	if policy.TotalDailyLimitMin != nil {
		t.Errorf("expected nil total limit, got %d", *policy.TotalDailyLimitMin)
	}
}

func TestPolicyVersionBump(t *testing.T) {
	s := newTestStore(t)
	dev, _ := mustCreateDevice(t, s, "phone-1")

	v1, err := s.PolicyVersion(dev.ID)
	if err != nil {
		t.Fatalf("PolicyVersion: %v", err)
	}
	if v1 != 1 {
		t.Errorf("expected initial version 1, got %d", v1)
	}

	// Set a limit should bump version
	s.SetLimit(dev.ID, domain.KindApp, "com.game", 30)
	v2, _ := s.PolicyVersion(dev.ID)
	if v2 != v1+1 {
		t.Errorf("expected version %d, got %d", v1+1, v2)
	}

	// Delete limit bumps version
	policy, _ := s.GetPolicy(dev.ID)
	s.DeleteLimit(policy.Limits[0].ID)
	v3, _ := s.PolicyVersion(dev.ID)
	if v3 != v2+1 {
		t.Errorf("expected version %d, got %d", v2+1, v3)
	}

	// Add exclusion bumps
	s.AddExclusion(dev.ID, domain.KindApp, "com.duolingo")
	v4, _ := s.PolicyVersion(dev.ID)
	if v4 != v3+1 {
		t.Errorf("expected version %d, got %d", v3+1, v4)
	}

	// Delete exclusion bumps
	excPolicy, _ := s.GetPolicy(dev.ID)
	s.DeleteExclusion(excPolicy.Exclusions[0].ID)
	v5, _ := s.PolicyVersion(dev.ID)
	if v5 != v4+1 {
		t.Errorf("expected version %d, got %d", v4+1, v5)
	}

	// Total limit update bumps
	s.SetTotalLimit(dev.ID, intPtr(120))
	v6, _ := s.PolicyVersion(dev.ID)
	if v6 != v5+1 {
		t.Errorf("expected version %d, got %d", v5+1, v6)
	}

	// Warn percent update bumps
	s.SetWarnPercent(dev.ID, 80)
	v7, _ := s.PolicyVersion(dev.ID)
	if v7 != v6+1 {
		t.Errorf("expected version %d, got %d", v6+1, v7)
	}
}

func TestGetPolicy_Full(t *testing.T) {
	s := newTestStore(t)
	dev, _ := mustCreateDevice(t, s, "phone-1")

	s.SetLimit(dev.ID, domain.KindApp, "com.game", 30)
	s.SetLimit(dev.ID, domain.KindWeb, "tiktok.com", 15)
	s.AddExclusion(dev.ID, domain.KindApp, "com.duolingo")
	s.AddExclusion(dev.ID, domain.KindWeb, "khanacademy.org")
	s.SetTotalLimit(dev.ID, intPtr(120))
	s.SetWarnPercent(dev.ID, 85)

	policy, err := s.GetPolicy(dev.ID)
	if err != nil {
		t.Fatalf("GetPolicy: %v", err)
	}

	if policy.WarnThresholdPercent != 85 {
		t.Errorf("expected warn 85, got %d", policy.WarnThresholdPercent)
	}
	if *policy.TotalDailyLimitMin != 120 {
		t.Errorf("expected total 120, got %d", *policy.TotalDailyLimitMin)
	}
	if len(policy.Limits) != 2 {
		t.Errorf("expected 2 limits, got %d", len(policy.Limits))
	}
	if len(policy.Exclusions) != 2 {
		t.Errorf("expected 2 exclusions, got %d", len(policy.Exclusions))
	}
}

func intPtr(v int) *int { return &v }
