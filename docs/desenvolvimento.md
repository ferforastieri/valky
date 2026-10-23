# Desenvolvimento

Guia para quem vai contribuir no monorepo: ambiente, comandos, testes, convenções e entrega.

## Preparar o ambiente

| Ferramenta | Versão usada pelo projeto |
| --- | --- |
| Go | 1.26.x (o `go.mod` fixa 1.26.10; o build exige `CGO_ENABLED=1` por causa do sherpa-onnx). |
| Node | 22, com pnpm 10.15 (`corepack enable` resolve a versão do `packageManager`). |
| JDK | 21 (Temurin) e Android SDK 36 com build-tools 36.0.0; o wrapper usa Gradle 9.5. |
| Docker | Engine com Compose v2, para a stack local e para a imagem. |

O repositório não tem submódulos nem geração de código obrigatória. A marca sai de `python3 scripts/brand-assets.py` (precisa de `rsvg-convert`) e a chave local da Tuya de `scripts/tuya-local-key.py` — nenhum dos dois é necessário para compilar.

## Rodar cada parte

**Stack completa:**

```bash
cp .env.example .env
docker compose up --build
curl -k https://localhost:8443/health
```

**Backend** (escuta `:8443`, cria `backend/data` e um certificado autoassinado):

```bash
cd backend
go run ./cmd/valky
```

**Landing e painel:**

```bash
cd web
corepack enable
pnpm install
pnpm dev           # landing em modo de desenvolvimento
pnpm build:viewer  # gera web/dist-viewer
```

Para ver o painel com o backend local, rode `pnpm build:viewer` e suba o backend com `VALKY_WEB_DIR` apontando para o caminho absoluto de `web/dist-viewer`; acesse `/app/` na mesma origem da API.

**Android:** abra `mobile/` no Android Studio ou use o wrapper. Sem `google-services.json` o APK compila e funciona; apenas não registra token FCM.

## Testes e lint

| Diretório | Comando | Cobre |
| --- | --- | --- |
| `backend/` | `gofmt -l .` | Formatação (o CI falha se houver diff). |
| `backend/` | `go vet ./...` | Análise estática. |
| `backend/` | `go run golang.org/x/vuln/cmd/govulncheck@v1.1.4 ./...` | Vulnerabilidades conhecidas. |
| `backend/` | `go test -race ./...` | Testes, incluindo a matriz de autorização e o detector de áudio. |
| `web/` | `pnpm test` | Typecheck, build da landing e do painel e testes Node. |
| `mobile/` | `./gradlew lintDebug testDebugUnitTest` | Lint e testes unitários. |
| raiz | `make test` | Os três conjuntos de uma vez. |

O teste opcional do classificador com o modelo real usa o pacote oficial extraído (o SHA-256 está fixado no Dockerfile):

```sh
VALKY_AUDIO_TEST_MODEL_DIR=/caminho/sherpa-onnx-zipformer-small-audio-tagging-2024-04-15 go test ./internal/detector -run TestNativeAudioReference -v
```

## Testes de captura

Os testes de screenshot renderizam as telas no JVM (Roborazzi/Robolectric) e baixam o runtime do Android na primeira execução; por isso ficam **fora do CI** e só rodam com a propriedade `-Pscreenshots`:

```bash
cd mobile
./gradlew :app:recordRoborazziDebug -Pscreenshots --tests '*LandingScreenshotsTest*'
```

As imagens saem em `mobile/app/build/screenshots/` e as usadas pela landing são copiadas para `web/public/screenshots/` com os nomes da tabela em `operacao.md`.

## Testes de autorização

`backend/internal/api/authorization_matrix_test.go` lê o próprio `server.go`, descobre todas as rotas registradas no mux `protected` e exige `401` sem credencial — inclusive com `isAdmin` forjado no corpo e no cabeçalho. Em seguida, confere que operações administrativas respondem `403` para um membro comum. Rotas novas entram automaticamente na primeira checagem; se a rota exigir administrador, inclua-a também na lista de operações administrativas do teste. Toda rota nova precisa desse teste de autorização.

## Convenções

- **Comentários em português**, curtos, explicando o porquê (o padrão do repositório). Identificadores de código em inglês.
- **Go:** um pacote por domínio em `backend/internal/`; erros em minúsculas; sem dependência nova sem motivo forte.
- **Android:** uma pasta por feature em `feature/`; ViewModel + Compose; textos sempre por `stringResource`, com pt-BR em `res/values/strings.xml` e inglês em `res/values-en/strings.xml` (as duas listas têm o mesmo conjunto de chaves).
- **Landing:** textos bilíngues em `web/src/content/copy.ts`; o site lê `docs/*.md` como fonte (`web/scripts/sync-docs.mjs`).
- **Painel:** textos em português direto em `web/viewer/`.
- **Estilos:** `web/src/styles/global.css` (landing) e `web/viewer/viewer.css` (painel) têm cascata própria; confira o efeito nas duas páginas antes de mexer.
- **Nomes:** features do app em `feature/<área>/`, rotas da API em kebab-case com plural (`/cameras`, `/settings/push`), variáveis de ambiente sempre `VALKY_*`.

### Adicionar uma rota no backend

1. Registre no mux `protected` de `backend/internal/api/server.go`, com o middleware de permissão certo (`RequireAdmin` ou `RequireRules`).
2. Documente em `backend/internal/api/openapi.yaml` (é o contrato servido em `/openapi.yaml` e publicado na release).
3. Rode `go test ./internal/api/...` para o teste de autorização cobrir a rota; se for administrativa, inclua-a na lista de operações do teste.
4. Se o app for usar, adicione o método em `mobile/app/src/main/java/com/ferforastieri/valky/core/network/ValkyrisApi.kt` e o fluxo no repositório.

### Adicionar uma tela no app

1. Crie a pasta em `feature/<área>/` com a tela e o ViewModel, no padrão das existentes.
2. Registre a navegação em `navigation/`.
3. Adicione as strings nos dois idiomas (`values/strings.xml` e `values-en/strings.xml`).
4. Se for uma tela de destaque da landing, acrescente o caso no `LandingScreenshotsTest` e a imagem em `web/public/screenshots/`.

### Adicionar um texto no painel

Edite `web/viewer/` (textos em português; `viewer.css` se houver estilo novo), rode `pnpm build:viewer` e confira em `/app/`. Lembre que o painel tem escopo de consulta: edição completa de regras, PTZ, reconhecimento e localização ficam no Android.

## Entrega

**CI.** Em pushes para `main` e em pull requests, o workflow classifica os caminhos alterados e roda apenas o necessário: backend (gofmt, vet, govulncheck, testes com `-race` e build), web (typecheck, build e testes), Android (lint e testes, com o APK debug como artefato) e container (build da imagem, `docker compose config` e smoke test HTTPS com o painel). Mudanças em `.github/workflows/*.yml` rodam tudo; `compose.yaml`, `mediamtx.yml`, `install.sh` e `.dockerignore` rodam o job de container.

**Versão.** Todo commit em `main` que concluir a CI com sucesso gera a próxima versão. Ela nasce sempre da última tag publicada (`patch + 1`), sem pular número; a mensagem do commit decide o resto: `feat:` sobe a minor e `!` ou `BREAKING` sobe a major. Mensagens começando com `docs:`, `chore:`, `style:`, `test:`, `ci:` ou `refactor:` (com ou sem escopo, como `chore(x):`) não publicam release. Não crie tags nem rode release manualmente.

**O que a release publica:** imagem multiarch (`amd64`/`arm64`) no GHCR, APK universal assinado, `compose.yaml`, `mediamtx.yml`, `install.sh`, `openapi.yaml`, `VERSION` e `SHA256SUMS`, com SBOM, proveniência e a GitHub Release correspondente. A tag `latest` da imagem só é promovida quando a versão publicada é a mais nova.

**No servidor**, atualizar é rodar o instalador de novo; o app avisa quando há APK novo e exige confirmação do sistema para instalar.
