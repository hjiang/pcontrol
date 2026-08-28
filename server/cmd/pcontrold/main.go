package main

import (
	"bufio"
	"context"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
	_ "time/tzdata"

	"golang.org/x/crypto/bcrypt"
	"pcontrol/server/internal/report"
	"pcontrol/server/internal/store"
	"pcontrol/server/internal/web"
)

func main() {
	listen := flag.String("listen", "127.0.0.1:8080", "HTTP listen address")
	db := flag.String("db", "pcontrol.db", "SQLite database path")
	adminHash := flag.String("admin-password-hash", "", "bcrypt hash of admin password (env: PCONTROL_ADMIN_HASH)")
	smtpHost := flag.String("smtp-host", "", "SMTP host for daily email reports (env: PCONTROL_SMTP_HOST)")
	smtpPort := flag.Int("smtp-port", 587, "SMTP port (env: PCONTROL_SMTP_PORT)")
	smtpUsername := flag.String("smtp-username", "", "SMTP username (env: PCONTROL_SMTP_USERNAME)")
	smtpPassword := flag.String("smtp-password", "", "SMTP password (env: PCONTROL_SMTP_PASSWORD)")
	smtpFrom := flag.String("smtp-from", "", "From address for daily email reports (env: PCONTROL_SMTP_FROM)")
	reportSendAfter := flag.Duration("report-send-after", 3*time.Hour, "delay after local midnight before sending daily reports (env: PCONTROL_REPORT_SEND_AFTER)")
	flag.Parse()

	if *adminHash == "" {
		*adminHash = os.Getenv("PCONTROL_ADMIN_HASH")
	}
	if *smtpHost == "" {
		*smtpHost = os.Getenv("PCONTROL_SMTP_HOST")
	}
	if v := os.Getenv("PCONTROL_SMTP_PORT"); v != "" {
		if p, err := strconv.Atoi(v); err == nil {
			*smtpPort = p
		} else {
			log.Printf("warning: invalid PCONTROL_SMTP_PORT %q, using %d", v, *smtpPort)
		}
	}
	if *smtpUsername == "" {
		*smtpUsername = os.Getenv("PCONTROL_SMTP_USERNAME")
	}
	if *smtpPassword == "" {
		*smtpPassword = os.Getenv("PCONTROL_SMTP_PASSWORD")
	}
	if *smtpFrom == "" {
		*smtpFrom = os.Getenv("PCONTROL_SMTP_FROM")
	}
	if v := os.Getenv("PCONTROL_REPORT_SEND_AFTER"); v != "" {
		if d, err := time.ParseDuration(v); err == nil {
			*reportSendAfter = d
		} else {
			log.Printf("warning: invalid PCONTROL_REPORT_SEND_AFTER %q, using %s", v, *reportSendAfter)
		}
	}

	// Handle subcommands
	if flag.NArg() > 0 {
		switch flag.Arg(0) {
		case "hash-password":
			hashPassword()
			return
		case "healthcheck":
			healthcheck()
			return
		default:
			fmt.Fprintf(os.Stderr, "unknown subcommand: %s\n", flag.Arg(0))
			os.Exit(1)
		}
	}

	if *adminHash == "" {
		log.Fatal("--admin-password-hash or PCONTROL_ADMIN_HASH is required")
	}

	s, err := store.Open(*db)
	if err != nil {
		log.Fatalf("open store: %v", err)
	}
	defer s.Close()

	mux := web.NewRouter(s, *adminHash)

	// Start the daily-email-report job when SMTP is configured. The job runs
	// on its own goroutine and is deliberately independent of the HTTP
	// server (the dashboard is pull-only; reports are push-only).
	if *smtpHost == "" {
		log.Printf("daily email reports disabled (no SMTP host configured)")
	} else {
		cfg := report.Config{
			Host:      *smtpHost,
			Port:      *smtpPort,
			Username:  *smtpUsername,
			Password:  *smtpPassword,
			From:      *smtpFrom,
			SendAfter: *reportSendAfter,
		}
		go report.NewSender(s, cfg).Run(context.Background())
		log.Printf("daily email reports enabled via %s", cfg.Addr())
	}

	log.Printf("pcontrold listening on %s", *listen)
	if err := http.ListenAndServe(*listen, mux); err != nil {
		log.Fatalf("server: %v", err)
	}
}

// healthcheck verifies the server is alive by hitting the /healthz endpoint.
// Intended for use as a Docker HEALTHCHECK command.
// Accepts an optional URL argument (default: http://127.0.0.1:7285/healthz).
func healthcheck() {
	url := "http://127.0.0.1:7285/healthz"
	if flag.NArg() > 1 {
		url = flag.Arg(1)
	}
	if flag.NArg() > 2 {
		fmt.Fprintf(os.Stderr, "usage: %s healthcheck [url]\n", os.Args[0])
		os.Exit(2)
	}
	client := &http.Client{Timeout: 5 * time.Second}
	resp, err := client.Get(url)
	if err != nil {
		fmt.Fprintf(os.Stderr, "healthcheck failed: %v\n", err)
		os.Exit(1)
	}
	defer resp.Body.Close()
	if _, err := io.Copy(io.Discard, resp.Body); err != nil {
		fmt.Fprintf(os.Stderr, "healthcheck body read failed: %v\n", err)
		os.Exit(1)
	}
	if resp.StatusCode != http.StatusOK {
		fmt.Fprintf(os.Stderr, "healthcheck returned %d\n", resp.StatusCode)
		os.Exit(1)
	}
	fmt.Println("ok")
}

// hashPassword reads a password from stdin (first line) and prints its bcrypt hash.
func hashPassword() {
	if isTerminal() {
		fmt.Fprint(os.Stderr, "Enter password: ")
	}
	reader := bufio.NewReader(os.Stdin)
	password, err := reader.ReadString('\n')
	if err != nil {
		fmt.Fprintf(os.Stderr, "error reading password: %v\n", err)
		os.Exit(1)
	}
	password = strings.TrimRight(password, "\n\r")

	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		fmt.Fprintf(os.Stderr, "error hashing password: %v\n", err)
		os.Exit(1)
	}

	fmt.Println(string(hash))
}

func isTerminal() bool {
	fi, err := os.Stdin.Stat()
	if err != nil {
		return false
	}
	return fi.Mode()&os.ModeCharDevice != 0
}
