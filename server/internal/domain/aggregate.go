package domain

import "strings"

// CountedTotalSeconds computes the "counted total" for the total daily limit:
//
//	countedTotal = Σ appSeconds(p)   for every package p NOT in app-exclusions
//	             + Σ webSeconds(d)   for every domain d NOT in web-exclusions
//
// The result is clamped at 0.
func CountedTotalSeconds(appTotals, webTotals []UsageTotal, exclusions []Exclusion) int {
	excluded := make(map[string]bool)
	for _, e := range exclusions {
		excluded[string(e.Kind)+":"+e.Subject] = true
	}

	var total int
	for _, ut := range appTotals {
		if !excluded[string(KindApp)+":"+ut.Subject] {
			total += ut.Seconds
		}
	}
	for _, ut := range webTotals {
		if !excluded[string(KindWeb)+":"+ut.Subject] {
			total += ut.Seconds
		}
	}
	if total < 0 {
		return 0
	}
	return total
}

// MatchesDomain reports whether the limit/exclusion subject matches the
// given domain. Matching is suffix-based on dot boundaries and
// case-insensitive: "youtube.com" matches "youtube.com", "m.youtube.com",
// "music.youtube.com", but NOT "notyoutube.com".
func MatchesDomain(subject, domain string) bool {
	subject = strings.ToLower(subject)
	domain = strings.ToLower(domain)

	if subject == domain {
		return true
	}
	// Suffix match on dot boundary: domain ends with "." + subject
	return strings.HasSuffix(domain, "."+subject)
}
