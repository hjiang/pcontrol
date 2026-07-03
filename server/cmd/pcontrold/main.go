package main

import (
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"

	"pcontrol/server/internal/web"
)

func main() {
	listen := flag.String("listen", "127.0.0.1:8080", "HTTP listen address")
	db := flag.String("db", "pcontrol.db", "SQLite database path")
	adminHash := flag.String("admin-password-hash", "", "bcrypt hash of admin password (env: PCONTROL_ADMIN_HASH)")
	flag.Parse()

	if *adminHash == "" {
		*adminHash = os.Getenv("PCONTROL_ADMIN_HASH")
	}

	if flag.Arg(0) == "hash-password" {
		// hash-password subcommand: reads password from stdin, prints bcrypt hash
		fmt.Fprintln(os.Stderr, "hash-password subcommand not yet implemented")
		os.Exit(1)
	}

	_ = db // SQLite store will be wired in Stage 2
	_ = adminHash

	mux := web.NewRouter()

	log.Printf("pcontrold listening on %s", *listen)
	if err := http.ListenAndServe(*listen, mux); err != nil {
		log.Fatalf("server: %v", err)
	}
}
