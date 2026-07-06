# Multi-stage build for pcontrold.
#
# Smoke test (run from repo root):
#   docker build -t pcontrol-server server/
#   docker run --rm -i pcontrol-server hash-password <<< 'x'
#   mkdir -p ./tmp && docker run -d --name pcontrol-test -p 7285:7285 \
#     -e PCONTROL_ADMIN_HASH='...' -v $(pwd)/tmp:/data pcontrol-server
#   docker exec pcontrol-test /pcontrold healthcheck
#   docker stop pcontrol-test && docker rm pcontrol-test

FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o /pcontrold ./cmd/pcontrold
# Pre-create /data world-writable so any --user (e.g. Unraid's 99:100
# or distroless nonroot 65532) can create /data/pcontrol.db.
RUN mkdir -p /data && chmod 1777 /data

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /pcontrold /pcontrold
COPY --from=build --chown=65532:65532 /data /data
VOLUME /data
EXPOSE 7285
# /healthz is an unauthenticated 200-OK endpoint implemented in web.NewRouter.
HEALTHCHECK CMD ["/pcontrold", "healthcheck"]
ENTRYPOINT ["/pcontrold"]
CMD ["--listen", "0.0.0.0:7285", "--db", "/data/pcontrol.db"]
