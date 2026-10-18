# AGENTS.md

Instruções para agentes de IA (Claude, Codex, OpenCode e afins) que trabalham neste repositório. Leia antes de editar qualquer coisa.

## Mapa do repositório

| Caminho | O que vive aqui e o que muda |
| --- | --- |
| `backend/` | API Go (domínio, ONVIF, mídia, detectores, regras, notificações, persistência). Rotas em `internal/api/server.go`; contrato em `internal/api/openapi.yaml`; variáveis em `internal/config/config.go`; esquema em `internal/store/schema.sql`. |
| `mobile/` | App Android em Kotlin/Compose (Hilt, Ktor/OkHttp, DataStore, Media3, osmdroid, FCM). Telas em `app/src/main/java/com/ferforastieri/valky/feature/`; textos em `res/values/strings.xml` (pt-BR) e `res/values-en/strings.xml`. |
| `web/` | Landing e documentação Astro em `src/`. O painel servido em `/app/` vive em `viewer/` e é buildado por `astro.viewer.config.mjs` para `dist-viewer/`. `scripts/sync-docs.mjs` copia `docs/*.md` para o site; `scripts/sync-public-assets.mjs` copia `install.sh` e o OpenAPI para `public/`. |
| `docs/` | Documentação versionada (7 arquivos) e fonte do site. |
| `scripts/` | `brand-assets.py` gera símbolo, ícones, splash e favicon; `tuya-local-key.py` obtém a chave local da lâmpada. |
| `.github/workflows/` | `ci.yml` (classifica caminhos alterados e roda a área afetada) e `release.yml` (versão automática, imagem GHCR, APK, artefatos). |
| raiz | `compose.yaml`, `mediamtx.yml`, `install.sh`, `Makefile`, `.env.example`. |

## Comandos que importam

| Diretório | Comando | Para quê |
| --- | --- | --- |
| `backend/` | `go test -race ./...` | Testes (o CI roda com `-race`). |
| `backend/` | `gofmt -l .` e `go vet ./...` | Formatação e análise estática (o CI falha se houver diff). |
| `backend/` | `go run golang.org/x/vuln/cmd/govulncheck@v1.1.4 ./...` | Vulnerabilidades conhecidas (faz parte do CI). |
| `backend/` | `CGO_ENABLED=1 go build -trimpath ./cmd/valky` | Compilação como no CI (sherpa-onnx exige CGO). |
| `backend/` | `go run ./cmd/valky` | Backend local (cria `./data` e o certificado autoassinado). |
| `backend/` | `go run ./cmd/lightscan` (`-probe`) | Lista lâmpadas Tuya na rede e testa a porta de controle. |
| `web/` | `pnpm test` | Typecheck, build da landing e do painel e testes Node. |
| `web/` | `pnpm dev` / `pnpm build` / `pnpm build:viewer` | Landing em dev, build da landing, build do painel. |
| `mobile/` | `./gradlew lintDebug testDebugUnitTest` | Lint e testes (o que o CI roda). |
| `mobile/` | `./gradlew assembleDebug` | APK de desenvolvimento. |
| `mobile/` | `./gradlew :app:recordRoborazziDebug -Pscreenshots --tests '*LandingScreenshotsTest*'` | Regrava as capturas da landing (fora do CI). |
| raiz | `make test` / `make build` / `make web` / `make mobile` / `make docker` | Atalhos para testes, binário Go, landing, Android e imagem Docker. |

## Como rodar a stack local

```bash
cp .env.example .env
docker compose up --build
curl -k https://localhost:8443/health
```

- Backend fora do Docker: `cd backend && go run ./cmd/valky` (escuta `:8443`; a base fica em `backend/data`).
- Painel no backend local: `cd web && pnpm build:viewer` e suba o backend com `VALKY_WEB_DIR` apontando para o caminho absoluto de `web/dist-viewer`.
- Android: abra `mobile/` no Android Studio ou use o wrapper. Sem `google-services.json` o APK compila e funciona; só não registra token FCM.
- O Compose usa a rede fixa `172.28.0.0/24` e o volume tmpfs `recording-buffer`; não troque isso sem ajustar `mediamtx.yml`.

## Invariantes (não quebre)

- O app nunca recebe a senha da câmera: credenciais são cifradas no backend e a API nunca as devolve.
- O MediaMTX não vê o volume `valky-data`; ele recebe apenas `recording-buffer` (tmpfs). Banco, chave-mestra e certificados são só do backend.
- Toda rota nova no mux `protected` entra automaticamente em `backend/internal/api/authorization_matrix_test.go`: sem credencial precisa dar 401 e operação administrativa precisa dar 403 para membro comum.
- O MediaMTX autoriza apenas `127.0.0.1`, `::1` e a sub-rede `172.28.0.0/24` (`mediamtx.yml`). Não amplie a faixa.
- O APK inclui apenas `arm64-v8a` e `armeabi-v7a` (`mobile/app/build.gradle.kts`); não adicione ABIs de emulador.
- A marca sai de `scripts/brand-assets.py`; não edite ícones gerados à mão.
- Segredos nunca entram no repositório: `master.key`, `valky.db`, `google-services.json`, keystore e conta de serviço FCM ficam fora do Git ou em secrets do GitHub.
- Atualização do servidor é manual, pelo instalador; não existe rota para o backend executar updates (a antiga `POST /system/update` foi removida de propósito).
- Migrações SQLite rodam automaticamente em `store.Open`; nunca edite uma migração já publicada, adicione uma nova.
- Mensagens de commit seguem a convenção que a pipeline lê (veja Entrega).

## Convenções de código

- Comentários em português, curtos, explicando o porquê. Identificadores de código em inglês.
- Go: um pacote por domínio em `backend/internal/`; erros em minúsculas; sem dependência nova sem motivo forte.
- Android: uma pasta por feature em `feature/`, ViewModel + Compose, textos sempre por `stringResource` (pt-BR em `values/`, inglês em `values-en/`).
- Painel: textos em português em `web/viewer/`. A landing é bilíngue e os textos ficam em `web/src/content/copy.ts`.
- Ao adicionar uma rota: registre no mux `protected` de `server.go`, documente em `openapi.yaml`, adicione o teste de autorização (automático pelo parser, mas confira a matriz de admin) e, se o app usar, o método em `core/network/ValkyrisApi.kt`.
- Ao adicionar uma tela no app: crie a pasta em `feature/`, o item de navegação em `navigation/`, strings nos dois idiomas e, se for uma tela de destaque, a captura da landing.
- Ao adicionar texto no painel: edite `web/viewer/` (e `viewer.css` se houver estilo novo) e rode `pnpm build:viewer`.

## Armadilhas conhecidas

- `web/src/styles/global.css` e `web/viewer/viewer.css` têm cascata própria: uma regra nova pode vazar para a outra página. Confira o efeito nas duas.
- O painel é servido em `/app/` com base `/app`: caminhos absolutos de asset precisam do prefixo.
- Os testes de captura do Android ficam fora do CI (baixam o runtime do Robolectric na primeira execução) e só rodam com `-Pscreenshots`.
- `govulncheck` faz parte do CI: dependência com falha conhecida quebra o build.
- `docs/*.md` é a fonte do site: criar ou renomear arquivo fora da lista de `sync-docs.mjs` muda a página de documentação.
- O instalador preserva `VALKY_WEBRTC_HOSTS` e `VALKY_SETUP_TOKEN` no `.env`; não os sobrescreva.
- O painel não tem edição completa de regras, PTZ, reconhecimento nem localização: isso é decisão de escopo, não bug.
- A câmera usa uma única conexão RTSP por câmera (`camera-<id>-source`); snapshots e detecção leem o stream interno, nunca abrem outra sessão na câmera.

## Entrega

1. Commit em `main` (ou merge) dispara a CI: Go (gofmt, vet, govulncheck, testes, build), web (typecheck/build/testes), Android (lint, testes) e container (build + smoke HTTPS), conforme os caminhos alterados.
2. CI verde em `main` dispara a release. A próxima versão nasce da última tag publicada (`patch + 1`); `feat:` sobe a minor; `!` ou `BREAKING` sobe a major.
3. Mensagens começando com `docs:`, `chore:`, `style:`, `test:`, `ci:` ou `refactor:` (com ou sem escopo) não publicam release.
4. A release publica imagem multiarch no GHCR, APK assinado, `compose.yaml`, `mediamtx.yml`, `install.sh`, `openapi.yaml`, `VERSION` e `SHA256SUMS`, com SBOM e proveniência.
5. No servidor, atualizar é rodar o instalador de novo. O app avisa quando há APK novo com um sheet obrigatório; o servidor nunca instala APK.
