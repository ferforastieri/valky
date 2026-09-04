<p align="center">
  <img src="web/public/valkyris-mark.svg" width="112" height="112" alt="Logo do Valkyris">
</p>

<h1 align="center">Valkyris</h1>

<p align="center">
  Monitoramento residencial self-hosted para câmeras ONVIF e RTSP.<br>
  Vídeo, regras e eventos permanecem na sua casa; os alertas chegam a um app Android nativo.
</p>

<p align="center">
  <a href="https://valkyris.vercel.app/">Site</a> ·
  <a href="https://valkyris.vercel.app/pt-BR/docs">Documentação</a> ·
  <a href="https://github.com/ferforastieri/valkyris/releases/latest">Última release</a> ·
  <a href="LICENSE">Licença MIT</a>
</p>

> Valkyris é um software independente e não possui afiliação com TP-Link ou Tapo.

## O projeto

Valkyris transforma um servidor doméstico em uma central privada de monitoramento. Uma única instalação pode gerenciar várias câmeras e autorizar vários celulares, sem cadastro público e sem expor RTSP, ONVIF ou o MediaMTX na internet.

- Descoberta de capacidades e snapshots por ONVIF Profile S.
- Live view LL-HLS autenticado, com uma única conexão RTSP por câmera e conversão apenas do áudio G.711 para AAC.
- Movimento PTZ por pressionar e segurar, zoom e presets quando anunciados pela câmera.
- Cadastro assíncrono: a câmera aparece imediatamente e o progresso ou erro fica persistido.
- Regras para movimento e sons residenciais, com confiança, confirmações, agenda e cooldown.
- Eventos com snapshot, reconhecimento, notificação e clipe com pré/pós-evento.
- Credenciais de câmera cifradas com AES-256-GCM e tokens persistidos somente como hash.
- Atualização do backend pelo app e download do APK assinado diretamente da release no GitHub.
- Interface em PT-BR e inglês, temas claro/escuro e suporte a LAN ou VPN.

## Como funciona

```text
Câmera ONVIF / RTSP
         │
         ▼
  MediaMTX interno ─── LL-HLS autenticado ─── Android
         │
         ├── ONVIF: capabilities, eventos e PTZ
         ├── FFmpeg: snapshots, G.711 → AAC, detecção e buffer
         ▼
 Detectores locais → Regras → Evento + mídia → UnifiedPush / ntfy
```

O backend Go é o limite de segurança: o app nunca recebe a senha da câmera e o MediaMTX não publica portas no host. SQLite, certificados, segredos, snapshots e clipes vivem no volume persistente `valkyris-data`.

## Tecnologias

| Área | Tecnologias |
| --- | --- |
| Backend | Go 1.26, SQLite, ONVIF, FFmpeg, sherpa-onnx, WebSocket |
| Mídia | MediaMTX, RTSP, LL-HLS, MP4 |
| Android | Kotlin, Jetpack Compose, Material 3, Hilt, Room, DataStore, Ktor/OkHttp, Media3, Coil, UnifiedPush |
| Web | Astro, TypeScript, CSS, Lucide, geração estática para Vercel |
| Distribuição | Docker Compose, GHCR multiarch (`amd64`/`arm64`), GitHub Actions, APK assinado |

## Requisitos

- Linux `amd64` ou `arm64` com Docker Engine e Docker Compose v2.
- Android 8.0 (SDK 26) ou superior.
- Câmera ONVIF Profile S com RTSP; áudio é necessário para detecção sonora.
- Servidor, câmera e celular na mesma LAN, ou conectados por uma VPN privada.

Não encaminhe no roteador as portas RTSP/ONVIF da câmera nem as portas internas `8888` e `9997` do MediaMTX.

## Instalação

Prepare a câmera no Wi-Fi e crie uma credencial própria de ONVIF/RTSP. Em uma Tapo TC40, essa opção aparece como **Camera Account** nas configurações avançadas; ela não usa a senha da conta TP-Link. Uma reserva DHCP no roteador é recomendada para o IP não mudar.

No servidor, execute:

```bash
curl -fsSL https://valkyris.vercel.app/install.sh | sh
```

O instalador verifica o Compose, baixa os artefatos da release, cria os segredos e o certificado TLS, prepara os arquivos locais e inicia `valkyris`, `mediamtx` e `updater` de forma idempotente.

Depois:

1. Instale o APK da [última release](https://github.com/ferforastieri/valkyris/releases/latest).
2. Abra o app e informe a URL HTTPS pela qual o celular alcança o servidor.
3. No primeiro acesso, crie a senha da casa; esse dispositivo se torna administrador.
4. Cadastre a câmera com nome, ícone, IP, usuário e senha. Para Tapo, o RTSP principal é montado automaticamente.

Para acessar de fora de casa, use uma VPN como Tailscale ou WireGuard. Um proxy reverso como Caddy pode fornecer um certificado TLS reconhecido pelo Android para um domínio privado.

## Rodar para desenvolvimento

### Stack completa

```bash
cp .env.example .env
docker compose up --build
```

A API HTTPS fica em `https://localhost:8443` por padrão. Como o certificado local é autoassinado, o health check no terminal pode usar:

```bash
curl -k https://localhost:8443/health
```

### Backend

```bash
cd backend
go test ./...
go run ./cmd/valkyris
```

### Landing e documentação

```bash
cd web
corepack enable
pnpm install
pnpm dev
```

Para validar o build estático, os tipos e os testes:

```bash
pnpm test
```

### Android

Abra a pasta `mobile` no Android Studio ou execute:

```bash
cd mobile
./gradlew lintDebug testDebugUnitTest assembleDebug
```

O APK de desenvolvimento será criado em `mobile/app/build/outputs/apk/debug/`.

## Estrutura do monorepo

```text
backend/   API Go, domínio, ONVIF, mídia, detectores, regras e persistência
mobile/    aplicativo Android nativo em Kotlin e Jetpack Compose
web/       landing page e documentação estática em Astro
updater/   sidecar isolado para atualizações autorizadas pelo administrador
docs/      decisões de arquitetura e notas operacionais do repositório
```

## Configuração

O arquivo [.env.example](.env.example) lista as variáveis suportadas. As principais são:

| Variável | Função |
| --- | --- |
| `VALKYRIS_LISTEN` | Endereço interno do servidor HTTPS. |
| `VALKYRIS_DATA_DIR` | Diretório persistente para banco, mídia e segredos. |
| `VALKYRIS_DATABASE` | Caminho do banco SQLite. |
| `VALKYRIS_TLS_CERT` / `VALKYRIS_TLS_KEY` | Identidade TLS do backend. |
| `VALKYRIS_MASTER_KEY_FILE` | Chave usada para cifrar credenciais sensíveis. |
| `VALKYRIS_MEDIA_URL` / `VALKYRIS_MEDIA_API` | Endereços internos do MediaMTX. |
| `VALKYRIS_UPDATER_URL` / `VALKYRIS_UPDATER_TOKEN` | Canal privado do atualizador. |
| `VALKYRIS_RELEASE_API` | Release estável consultada pelo backend. |

Não existe `PUBLIC_URL`: cada celular informa a URL que realmente usa para alcançar sua instalação. Convites para outros dispositivos são criados dentro do app e combinados com essa URL localmente.

## Documentação

A documentação operacional está em **[valkyris.vercel.app/pt-BR/docs](https://valkyris.vercel.app/pt-BR/docs)**. Ela cobre:

- instalação, requisitos e preparação da Tapo TC40;
- arquitetura, segurança, backup e diagnóstico;
- conexão do Android, convites e atualização;
- todas as rotas HTTP, autenticação e envelopes de resposta;
- variáveis de configuração e comandos de operação.

O contrato completo e versionável está em [backend/internal/api/openapi.yaml](backend/internal/api/openapi.yaml) e também é publicado como [OpenAPI YAML](https://valkyris.vercel.app/openapi.yaml).

Exemplo autenticado:

```bash
curl -k https://SEU_SERVIDOR:8443/api/v1/cameras \
  -H 'Authorization: Bearer SEU_TOKEN'
```

Respostas JSON seguem um envelope consistente:

```json
{
  "success": true,
  "message": "Cameras listed",
  "data": []
}
```

## Testes e integração contínua

Em pushes e pull requests, o GitHub Actions executa formatação, análise estática e testes Go, build/smoke test Docker, lint/testes/APK Android e typecheck/build/testes do Astro. Todo commit enviado para `main` que concluir a CI com sucesso gera automaticamente a próxima versão, cria sua tag e publica:

- imagens multiarch do backend e atualizador no GHCR;
- APK universal assinado;
- Compose, configuração do MediaMTX, instalador e contrato OpenAPI;
- checksums SHA-256, SBOM, proveniência e GitHub Release.

Não é necessário criar tags nem executar comandos de release manualmente: basta fazer commit e push para `main`.

## Compatibilidade e limites

Valkyris apresenta apenas recursos anunciados por ONVIF/RTSP. Ausências degradam a interface sem impedir o restante da câmera. Recursos proprietários da Tapo que não fazem parte do Profile S — como áudio bidirecional, holofote, sirene, privacidade e patrulha — não são controlados pelo projeto.

Não há gravação contínua, nuvem central, cadastro público nem promessa de entrega absoluta de alarmes quando faltam energia, rede ou permissões do Android.

## Licença

Distribuído sob a [licença MIT](LICENSE). © 2026 Fernando Forastieri.
