package web

import "strings"

// friendlyLabels maps well-known Android package names to short labels.
var friendlyLabels = map[string]string{
	"com.google.android.youtube": "YouTube",
	"com.android.chrome":         "Chrome",
	"com.google.android.gm":      "Gmail",
	"com.zhiliaoapp.musically":   "TikTok",
	"com.instagram.android":      "Instagram",
	"com.snapchat.android":       "Snapchat",
	"com.discord":                "Discord",
	"com.spotify.music":          "Spotify",
	"com.mojang.minecraftpe":     "Minecraft",
	// extend as observed in real data
}

// friendlyLabel returns a human-readable label for an app package name.
// Websites/domains are already human-readable and must not go through this.
func friendlyLabel(pkg string) string {
	if f, ok := friendlyLabels[pkg]; ok {
		return f
	}
	// strip common namespace prefix: com.example.app → app
	parts := strings.Split(pkg, ".")
	if len(parts) > 2 {
		return parts[len(parts)-1]
	}
	return pkg
}
