.PHONY: test build web mobile docker

test:
	cd backend && go test -race ./...
	cd backend/activation && python3 -m unittest
	cd web && pnpm test
	cd mobile && ./gradlew lintDebug testDebugUnitTest

build:
	cd backend && CGO_ENABLED=1 go build -o valkyris ./cmd/valkyris

web:
	cd web && pnpm build

mobile:
	cd mobile && ./gradlew lintDebug testDebugUnitTest assembleDebug

docker:
	docker compose build
