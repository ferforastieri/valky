FROM golang:1.26-alpine AS builder
WORKDIR /src
COPY go.mod ./
COPY main.go ./
ARG TARGETARCH
RUN CGO_ENABLED=0 GOOS=linux GOARCH=${TARGETARCH} go build -trimpath -ldflags='-s -w' -o /out/valkyris-updater .

FROM alpine:3.22
RUN apk add --no-cache ca-certificates docker-cli docker-cli-compose
COPY --from=builder /out/valkyris-updater /usr/local/bin/valkyris-updater
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/valkyris-updater"]
