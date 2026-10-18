<p align="center">
  <img src="web/public/valky-mark.svg" width="112" height="112" alt="Logo do Valky">
</p>

<h1 align="center">Valky</h1>

<p align="center">
  Monitoramento e controle residencial self-hosted para câmeras e iluminação inteligente.<br>
  Vídeo, regras, eventos e comandos permanecem na sua casa; os alertas chegam a um app Android nativo.
</p>
<p align="center">
  <a href="https://valkyris.vercel.app/">Site</a> ·
  <a href="https://valkyris.vercel.app/pt-BR/docs">Documentação</a> ·
  <a href="https://github.com/ferforastieri/valky/releases/latest">Última release</a> ·
  <a href="LICENSE">Licença MIT</a>
</p>

## O que é

Valky transforma um servidor doméstico em uma central privada de monitoramento. Uma instalação gerencia várias câmeras ONVIF e vários celulares, sem cadastro público, sem nuvem central e sem expor RTSP, ONVIF ou o MediaMTX na internet.

O processamento fica no servidor: detecção, regras, mídia e notificações nascem na sua rede. O celular entra por LAN ou VPN com uma sessão autenticada e recebe os alertas como notificações e alarmes nativos.

- Câmeras ONVIF ao vivo por WebRTC, com PTZ, snapshot, clipes com pré/pós-evento e posições salvas na própria câmera.
- Detecção de pessoas, movimento em região e sons residenciais, com regras, confirmações, horário e cooldown.
- Iluminação Tuya controlada na rede local (cor, branco e brilho) e controle remoto de TVs pela mesma sessão.
- Localização da família com mapa, histórico e alertas de entrada e saída de áreas.
- Notificações e alarmes nativos no Android, widgets na tela inicial e painel web em `/app/` para consultar a casa.

## Como funciona

```text
Câmera ONVIF / RTSP
         │
         ▼
  MediaMTX interno ─── WebRTC direto ─── Android / painel web
         │
         ├── ONVIF: capabilities, eventos e PTZ
         ├── FFmpeg: snapshots, G.711 → Opus, detecção e buffer
         ▼
 Detectores locais → Regras → Evento + mídia → FCM → alerta nativo Android
```

```text
backend/   API Go, domínio, ONVIF, mídia, detectores, regras e persistência
mobile/    aplicativo Android nativo em Kotlin e Jetpack Compose
web/       landing/documentação Astro; viewer/ contém o painel servido em /app/
docs/      documentação versionada, publicada no site
scripts/   geração dos ativos da marca (scripts/brand-assets.py)
```

O backend Go é o limite de segurança: o app nunca recebe a senha da câmera e as APIs internas do MediaMTX não são expostas. Apenas a porta de mídia WebRTC 8189 UDP/TCP é publicada. Banco, certificados, segredos, snapshots e clipes ficam no volume `valky-data`, que só o container da aplicação enxerga; o MediaMTX recebe apenas o buffer temporário de dez minutos, em RAM (`recording-buffer`, 384 MiB). Detalhes em [arquitetura](docs/arquitetura.md) e [segurança](docs/seguranca.md).

## Requisitos

- Linux `amd64` ou `arm64` com Docker Engine e Docker Compose v2.
- Android 8.0 (SDK 26) ou superior, câmera ONVIF Profile S com RTSP e áudio para detecção sonora.
- Servidor, câmera e celular na mesma LAN, ou conectados por uma VPN privada; para iluminação local, servidor e lâmpada precisam se alcançar pela rede doméstica.

Não encaminhe no roteador as portas RTSP/ONVIF da câmera nem as portas internas 8889 e 9997 do MediaMTX.

## Instalação

No servidor, execute:

```bash
curl -fsSL https://valkyris.vercel.app/install.sh | sh
```

O instalador baixa os artefatos da release, confere os checksums, cria os segredos, o código de instalação e o certificado TLS, e sobe `valky` e `mediamtx` de forma idempotente. Os arquivos ficam em `~/.local/share/valky`; o código de instalação (`VALKY_SETUP_TOKEN`) fica no `.env` e é impresso no fim da execução.

Depois:

1. Prepare a câmera no Wi-Fi e crie uma credencial própria de ONVIF/RTSP (na Tapo TC40, **Camera Account**).
2. Instale o APK da [última release](https://github.com/ferforastieri/valky/releases/latest) e informe a URL HTTPS pela qual o celular alcança o servidor.
3. No primeiro acesso, informe o código de instalação e crie a conta com usuário e senha; esse dispositivo se torna administrador.
4. Cadastre a câmera com nome, ícone, IP, usuário e senha. Para Tapo, o RTSP principal é montado automaticamente.

## Rodar para desenvolvimento

A API HTTPS fica em `https://localhost:8443` com certificado autoassinado; use `curl -k` para conferir o `/health`.

```bash
cp .env.example .env && docker compose up --build        # stack completa
cd backend && go test ./... && go run ./cmd/valky        # backend
cd web && corepack enable && pnpm install && pnpm dev    # landing
cd web && pnpm build:viewer                              # painel em /app/
cd mobile && ./gradlew lintDebug testDebugUnitTest assembleDebug  # Android
```

O APK de desenvolvimento sai em `mobile/app/build/outputs/apk/debug/`. As capturas da landing são geradas pelos testes do app (fora da CI):

```bash
cd mobile
./gradlew :app:recordRoborazziDebug -Pscreenshots --tests '*LandingScreenshotsTest*'
```

## Configuração

O arquivo [.env.example](.env.example) lista as variáveis suportadas. As principais:

| Variável | Função |
| --- | --- |
| `VALKY_PORT` | Porta publicada no host (padrão `8443`). |
| `VALKY_DATA_DIR` | Diretório persistente para banco, mídia e segredos. |
| `VALKY_TLS_CERT` / `VALKY_TLS_KEY` | Identidade TLS do backend. |
| `VALKY_MASTER_KEY_FILE` | Chave usada para cifrar credenciais sensíveis. |
| `VALKY_MEDIA_API` / `VALKY_MEDIA_RTSP` / `VALKY_MEDIA_WEBRTC` / `VALKY_MEDIA_PLAYBACK` | Endereços internos de configuração, monitoramento RTSP, negociação WHEP e reprodução de clipes do MediaMTX. |
| `VALKY_WEBRTC_HOSTS` | Endereços anunciados para a mídia WebRTC (LAN/VPN), separados por vírgula. |
| `VALKY_SETUP_TOKEN` | Código exigido para criar o primeiro administrador. |
| `VALKY_FIREBASE_CREDENTIALS_FILE` | Conta de serviço usada pelo backend para enviar alertas pelo FCM. |

## Documentação

- [Instalação](docs/instalacao.md): requisitos, câmera, instalador, FCM, acesso externo, backup e diagnóstico.
- [Arquitetura](docs/arquitetura.md): componentes, fluxos de mídia e detecção, volumes, dados e limites de confiança.
- [API](docs/api.md): autenticação, envelopes, códigos, limites e rotas principais.
- [Recursos](docs/recursos.md): câmeras, detecção, regras, eventos, iluminação, TVs, localização e painel.
- [Segurança](docs/seguranca.md): contas, tokens, permissões, cifra em repouso, cabeçalhos e revisões.
- [Operação](docs/operacao.md): energia, retenção, logs, diagnóstico e capturas.
- [Desenvolvimento](docs/desenvolvimento.md): ambiente, testes, convenções e entrega.
- [OpenAPI](backend/internal/api/openapi.yaml): contrato HTTP completo, também servido em `/openapi.yaml`.

## Licença

Distribuído sob a [licença MIT](LICENSE). © 2026 Fernando Forastieri.
