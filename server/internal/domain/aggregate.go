package domain

import "strings"

// CountedTotalSeconds computes the "counted total" for the total daily limit
// per §6:
//
//	countedTotal = Σ appSeconds(p)   for every package p NOT in app-exclusions
//	             − Σ webSeconds(d)   for every domain d in web-exclusions
//	clamped to ≥ 0
//
// Web time is a SUBSET of the browser's app time, so non-excluded web seconds
// are neither added nor subtracted (they are already counted inside the
// browser's app total). Excluded web seconds are subtracted so that, e.g.,
// Khan Academy in Chrome does not eat the daily budget.
//
// Exclusions match by suffix on dot boundaries (§6), so an exclusion on
// "khanacademy.org" also subtracts "www.khanacademy.org".
func CountedTotalSeconds(appTotals, webTotals []UsageTotal, exclusions []Exclusion) int {
	isExcluded := func(kind Kind, subject string) bool {
		for _, e := range exclusions {
			if e.Kind == kind && MatchesDomain(e.Subject, subject) {
				return true
			}
		}
		return false
	}

	var total int
	// Add non-excluded app seconds.
	for _, ut := range appTotals {
		if !isExcluded(KindApp, ut.Subject) {
			total += ut.Seconds
		}
	}
	// Subtract excluded web seconds (whitelisted browsing never digs the
	// deficit deeper).
	for _, ut := range webTotals {
		if isExcluded(KindWeb, ut.Subject) {
			total -= ut.Seconds
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
