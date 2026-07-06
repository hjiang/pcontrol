# Multi-stage build for pcontrold.
#
# Smoke test (run from repo root):
#   docker build -t pcontrol-server server/
#   docker run --rm -i pcontrol-server hash-password <<< 'x'
#   docker run --rm -e PCONTROL_ADMIN_HASH='...' -v $(pwd)/tmp:/data pcontrol-server
#
# The healthcheck subcommand verifies the server is alive:
#   docker run --rm pcontrol-server healthcheck   # requires a running instance on :7285

FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o /pcontrold ./cmd/pcontrold

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /pcontrold /pcontrold
VOLUME /data
EXPOSE 7285
HEALTHCHECK CMD ["/pcontrold", "healthcheck"]
ENTRYPOINT ["/pcontrold"]
CMD ["--listen", "0.0.0.0:7285", "--db", "/data/pcontrol.db"]
