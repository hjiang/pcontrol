package main

import (
	"bufio"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"
	"time"

	"pcontrol/server/internal/store"
	"pcontrol/server/internal/web"
	"golang.org/x/crypto/bcrypt"
)

func main() {
	listen := flag.String("listen", "127.0.0.1:8080", "HTTP listen address")
	db := flag.String("db", "pcontrol.db", "SQLite database path")
	adminHash := flag.String("admin-password-hash", "", "bcrypt hash of admin password (env: PCONTROL_ADMIN_HASH)")
	flag.Parse()

	if *adminHash == "" {
		*adminHash = os.Getenv("PCONTROL_ADMIN_HASH")
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
	client := &http.Client{Timeout: 5 * time.Second}
	resp, err := client.Get(url)
	if err != nil {
		fmt.Fprintf(os.Stderr, "healthcheck failed: %v\n", err)
		os.Exit(1)
	}
	resp.Body.Close()
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
