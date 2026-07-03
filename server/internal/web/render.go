package web

import (
	"bytes"
	"embed"
	"fmt"
	"html/template"
	"io"
	"log"
	"net/http"
)

//go:embed templates/*.gohtml
var templateFS embed.FS

//go:embed static/*
var staticFS embed.FS

// StaticHandler serves embedded static files (e.g. htmx.min.js).
func StaticHandler() http.Handler {
	return http.FileServer(http.FS(staticFS))
}

// parsedTemplates is the complete set of named templates.
var parsedTemplates *template.Template

// layoutData is passed to the layout template. ContentHTML holds the
// page-specific rendered HTML, which is injected verbatim (not escaped)
// into the <main> element.
type layoutData struct {
	ContentHTML template.HTML
}

func init() {
	var err error
	parsedTemplates, err = template.ParseFS(templateFS, "templates/*.gohtml")
	if err != nil {
		log.Fatalf("parse templates: %v", err)
	}
}

// renderPage renders the named page template with data, wraps it in the
// layout, and writes the result to w.
func renderPage(w io.Writer, pageName string, data interface{}) error {
	// Render page content into a buffer.
	var buf bytes.Buffer
	if err := parsedTemplates.ExecuteTemplate(&buf, pageName, data); err != nil {
		return fmt.Errorf("execute %s: %w", pageName, err)
	}

	// Wrap in layout.
	ld := layoutData{ContentHTML: template.HTML(buf.String())}
	if err := parsedTemplates.ExecuteTemplate(w, "layout.gohtml", ld); err != nil {
		return fmt.Errorf("render layout: %w", err)
	}
	return nil
}
