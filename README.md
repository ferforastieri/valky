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

## O projeto

Valky transforma um servidor doméstico em uma central privada de monitoramento. Uma única instalação pode gerenciar várias câmeras e autorizar vários celulares, sem cadastro público e sem expor RTSP, ONVIF ou o MediaMTX na internet.

- Descoberta de capacidades e do perfil principal por ONVIF Profile S; previews são extraídos do stream local já conectado.
- Live WebRTC autenticado, com uma única conexão RTSP por câmera e conversão apenas do áudio G.711 para Opus.
- Movimento PTZ por pressionar e segurar e zoom quando anunciado pela câmera.
- Posições PTZ salvas na própria câmera, com uma posição fixada para ela voltar sozinha ao iniciar ou reiniciar.
- Dispositivos favoritos (câmeras, lâmpadas e controles) na visão geral, agrupados por seção e com energia das lâmpadas ali mesmo, além de estatísticas de eventos por câmera.
- Widgets de tela inicial para câmera (última imagem), luzes (ligar e desligar) e controle remoto da TV.
- Cadastro assíncrono: a câmera aparece imediatamente e o progresso ou erro fica persistido.
- [Detecção sonora contínua](docs/audio-detection.md), com janelas sobrepostas, confirmação temporal de choro de bebê e diagnóstico de pontuações por 24 horas.
- Regras para movimento e sons residenciais; a confiança mínima é aplicada internamente por detector, sem ajuste manual, e as regras ficam sempre ativas.
- Mapa familiar com usuários vinculados aos dispositivos pareados, histórico de localização e alertas de entrada e saída de áreas.
- Eventos com snapshot, reconhecimento, notificação e clipe com pré/pós-evento.
- [Alertas personalizados por regra](docs/camera-alerts.md), com textos, toque, vibração e tela cheia configuráveis no Android.
- Administração de usuários no painel e Android, destinatários por regra e [autorização validada no servidor](docs/security-access.md).
- Credenciais de câmera cifradas com AES-256-GCM e tokens persistidos somente como hash.
- Avisos de nova versão por sheet obrigatório no app e download manual do APK assinado.
- Painel web em /app/, servido pelo próprio backend, para consultar câmeras, eventos, família e configurações.
- [Base de iluminação local](docs/lighting.md) independente de fabricante: basta adicionar uma lâmpada que já foi pareada no aplicativo do fabricante e depois controlá-la pela rede de casa — energia, brilho, branco ajustável e RGB — sem nuvem no dia a dia.
- [Controle remoto de TVs](docs/remote-control.md) pelo backend, com descoberta na rede, pareamento autorizado na TV e token cifrado.
- Interface em PT-BR e inglês, temas claro/escuro e suporte a LAN ou VPN.

## Destaques

- **Câmeras ONVIF** ao vivo (WebRTC), PTZ, snapshot e clipes por evento
- **Detecção** de pessoas, movimento e som (inclusive choro de bebê), com regras, confirmações e cooldown
- **Iluminação inteligente**: lâmpadas Wi-Fi compatíveis com Tuya cadastradas pelo próprio app e controladas **na rede local** (sem conta do fabricante e sem nuvem), com cor, branco, tom e brilho por modo
- **Controle de TV**: liga, desliga e acorda a TV pela rede, com teclado numérico e de letras digitando no campo da TV
- **Localização da família** com mapa, histórico e alertas de lugar
- **Notificações** no Android, marcadas como vistas ao abrir
- **Widgets** na tela inicial: câmeras, lâmpadas (rolagem com todas) e controle da TV
- **Painel web** para acompanhar e operar a casa pelo navegador
- Tema claro e escuro, interface em português e inglês

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

O backend Go é o limite de segurança: o app nunca recebe a senha da câmera e as APIs internas do MediaMTX não são expostas. Apenas a porta de mídia WebRTC 8189 UDP/TCP é publicada. SQLite, certificados, segredos, snapshots e clipes vivem no volume persistente `valky-data`, que só o container da aplicação enxerga — o MediaMTX recebe apenas o volume do buffer. A API do MediaMTX aceita somente a sub-rede fixa do projeto (`172.28.0.0/24`), não a faixa `172.16/12` inteira. O buffer temporário de dez minutos fica em RAM, no volume compartilhado `recording-buffer`, limitado a 384 MiB, evitando gravações contínuas em disco. Veja [dimensionamento e operação 24/7](docs/energy-efficiency.md).

## Tecnologias

| Área | Tecnologias |
| --- | --- |
| Backend | Go 1.26, SQLite, ONVIF, drivers locais de iluminação, FFmpeg, sherpa-onnx, WebSocket |
| Mídia | MediaMTX, RTSP, WebRTC/WHEP, MP4 |
| Android | Kotlin, Jetpack Compose, Material 3, Hilt, DataStore, Ktor/OkHttp, Media3, Coil, Firebase Cloud Messaging |
| Web | Astro, TypeScript, CSS, Lucide, landing estática e painel de consulta incluído na imagem Docker |
| Distribuição | Docker Compose, GHCR multiarch (`amd64`/`arm64`), GitHub Actions, APK assinado |

## Requisitos

- Linux `amd64` ou `arm64` com Docker Engine e Docker Compose v2.
- Android 8.0 (SDK 26) ou superior.
- Câmera ONVIF Profile S com RTSP; áudio é necessário para detecção sonora.
- Servidor, câmera e celular na mesma LAN, ou conectados por uma VPN privada.
- Para iluminação local, servidor e lâmpada precisam conseguir se alcançar pela rede doméstica.

Não encaminhe no roteador as portas RTSP/ONVIF da câmera nem as portas internas `8889` e `9997` do MediaMTX.

## Instalação

Prepare a câmera no Wi-Fi e crie uma credencial própria de ONVIF/RTSP. Em uma Tapo TC40, essa opção aparece como **Camera Account** nas configurações avançadas; ela não usa a senha da conta TP-Link.

No servidor, execute:

```bash
curl -fsSL https://valkyris.vercel.app/install.sh | sh
```

O instalador verifica o Compose, baixa os artefatos da release, cria os segredos, o código de instalação e o certificado TLS, prepara os arquivos locais e inicia `valky` e `mediamtx` de forma idempotente. O código de instalação (`VALKY_SETUP_TOKEN`) fica no `.env` e é impresso no fim da execução.

Depois:

1. Instale o APK da [última release](https://github.com/ferforastieri/valky/releases/latest).
2. Abra o app e informe a URL HTTPS pela qual o celular alcança o servidor.
3. No primeiro acesso, informe o código de instalação exibido pelo instalador e crie a conta com usuário e senha; esse dispositivo se torna administrador.
4. Cadastre a câmera com nome, ícone, IP, usuário e senha. Para Tapo, o RTSP principal é montado automaticamente.

Para acessar de fora de casa, use uma VPN como Tailscale ou WireGuard, ou publique o HTTPS por proxy/túnel. O Cloudflare Tunnel transporta a API e a negociação WHEP, mas não a mídia WebRTC: o cliente precisa alcançar a porta 8189 UDP/TCP por LAN, VPN ou outra rota ICE configurada. O instalador preserva VALKY_WEBRTC_HOSTS com o endereço do servidor; não use o domínio do túnel como endereço de mídia. Veja [conectividade WebRTC](docs/webrtc-connectivity.md).

Abra https://SEU_SERVIDOR/app/ e entre com seu usuário e senha para consultar o painel. Regras ficam nos detalhes da câmera; áreas e percursos ficam em Família. O painel permite gestão de usuários e convites para administradores, edição de destinatários para quem tem permissão e controle das lâmpadas já cadastradas. Cadastro e configuração de dispositivos, PTZ, edição completa das regras e marcar eventos como lidos continuam no Android.

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
go run ./cmd/valky
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
web/       landing/documentação Astro; viewer/ contém o painel servido em /app/
docs/      decisões de arquitetura e notas operacionais do repositório
scripts/   geração dos ativos da marca (scripts/brand-assets.py)
```

A marca (símbolo, ícones, splash e favicon) sai de uma fonte única com
`python3 scripts/brand-assets.py`, que precisa de `rsvg-convert`. As capturas de
tela da landing são geradas pelos testes do app e estão descritas em
[docs/capturas.md](docs/capturas.md).

## Configuração

O arquivo [.env.example](.env.example) lista as variáveis suportadas. As principais são:

| Variável | Função |
| --- | --- |
| `VALKY_LISTEN` | Endereço interno do servidor HTTPS. |
| `VALKY_DATA_DIR` | Diretório persistente para banco, mídia e segredos. |
| `VALKY_DATABASE` | Caminho do banco SQLite. |
| `VALKY_TLS_CERT` / `VALKY_TLS_KEY` | Identidade TLS do backend. |
| `VALKY_MASTER_KEY_FILE` | Chave usada para cifrar credenciais sensíveis. |
| `VALKY_MEDIA_API` / `VALKY_MEDIA_RTSP` / `VALKY_MEDIA_WEBRTC` / `VALKY_MEDIA_PLAYBACK` | Endereços internos de configuração, monitoramento RTSP, negociação WHEP/WebRTC e reprodução de clipes do MediaMTX. |
| `VALKY_RELEASE_API` | Release estável consultada pelo backend. |
| `VALKY_FIREBASE_CREDENTIALS_FILE` | Conta de serviço usada pelo backend para enviar alertas pelo FCM. |

Não existe `PUBLIC_URL`: cada celular informa a URL que realmente usa para alcançar sua instalação. Convites para outros dispositivos são criados dentro do app e combinados com essa URL localmente.

### Notificações nativas com FCM

O Valky não exige aplicativo auxiliar no celular. O APK recebe mensagens de dados pelo Firebase Cloud Messaging e cria localmente a notificação ou o alarme nativo. O conteúdo do evento continua cifrado de ponta a ponta entre o backend e o dispositivo.

1. No Firebase Console, crie um projeto, adicione o aplicativo Android `com.ferforastieri.valky` e baixe `google-services.json`.
2. Codifique esse arquivo em base64 e cadastre o conteúdo no secret do GitHub `FIREBASE_ANDROID_CONFIG_BASE64`; ele será incluído no APK durante a release.
3. Em **Configurações do projeto → Contas de serviço**, gere uma chave privada JSON. No primeiro sheet de alertas do app, escolha essa chave: o administrador a envia por TLS, o backend valida e cifra o JSON no SQLite. A conta nunca é retornada pela API, registrada no Git ou incluída na imagem.

O APK registra o token FCM automaticamente depois do login. No app, basta conceder a permissão de notificações; não existe configuração ou tentativa manual de registro. A chave de conta de serviço é privada e deve permanecer somente no servidor.

## Versionamento

O projeto segue **SemVer** (`MAJOR.MINOR.PATCH`):

- **MAJOR**: mudança que quebra compatibilidade (ex.: app antigo deixa de falar com o servidor novo)
- **MINOR**: recurso novo, compatível
- **PATCH**: correção

A próxima versão é sempre calculada a partir da **última publicada** (`patch + 1`), sem pular número. O pipeline lê a mensagem do commit: `feat:` sobe a **minor** e uma marcação de quebra (`!` ou `BREAKING`) sobe a **major**. As releases ficam publicadas em ordem, do começo ao mais recente, sempre com o código-fonte correspondente.

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

### Endpoints recentes

Além do contrato completo em OpenAPI, as rotas adicionadas recentemente são:

| Método | Rota | Acesso | Finalidade |
| --- | --- | --- | --- |
| `PUT` | `/api/v1/cameras/{id}` | Autenticado | Edita uma câmera e reconfigura o stream se a conexão mudar. |
| `PUT` | `/api/v1/rules/{id}` | Autenticado | Edita uma regra. |
| `GET` | `/api/v1/users` | Autenticado | Lista os usuários da família vinculados aos dispositivos pareados. |
| `GET` | `/api/v1/users/{id}/history` | Autenticado | Retorna o histórico de localização do usuário. |
| `POST` | `/api/v1/me/location` | Autenticado | Registra a localização do usuário vinculado ao dispositivo atual. |
| `GET · POST · PUT · DELETE` | `/api/v1/places` | Autenticado / Admin ao alterar | Lista áreas ou cria, altera e remove áreas de alerta. |
| `POST` | `/api/v1/events/acknowledge-all` | Autenticado | Marca todos os eventos pendentes como lidos. |
| `GET` | `/api/v1/settings/push` | Autenticado | Informa apenas se o FCM está configurado. |
| `PUT` | `/api/v1/settings/push` | Administrador | Recebe `{ "serviceAccountBase64": "..." }`, valida e cifra a conta de serviço no SQLite. |
| `GET · POST · PUT · DELETE` | `/api/v1/remotes` | Autenticado / Admin ao alterar | Lista, cadastra, edita e remove controles remotos de TV. |
| `POST` | `/api/v1/remotes/{id}/pair` | Administrador | Pareia com a TV; aceite o aviso exibido nela. |
| `POST` | `/api/v1/remotes/{id}/keys` | Autenticado | Envia uma tecla ao dispositivo pareado. |
| `GET` | `/api/v1/remotes/{id}/apps` | Autenticado | Lista os aplicativos instalados na TV. |
| `POST` | `/api/v1/remotes/{id}/apps/{app}/launch` | Autenticado | Abre um aplicativo instalado. |
| `POST` | `/api/v1/remotes/{id}/wake` | Autenticado | Envia o magic packet (Wake-on-LAN) para ligar a TV. |
| `GET · POST` | `/api/v1/cameras/{id}/ptz/presets` | Autenticado | Lista e salva posições PTZ na câmera. |
| `POST` | `/api/v1/cameras/{id}/ptz/presets/{token}/goto` | Autenticado | Move a câmera para uma posição salva. |
| `DELETE` | `/api/v1/cameras/{id}/ptz/presets/{token}` | Autenticado | Remove uma posição salva. |
| `PUT` | `/api/v1/cameras/{id}/ptz/startup` | Autenticado | Define a posição aplicada quando a câmera inicia. |
| `GET` | `/api/v1/cameras/{id}/stats` | Autenticado | Estatísticas agregadas dos eventos da câmera. |
| `PUT` | `/api/v1/favorites/{type}/{id}` | Autenticado | Marca/desmarca câmera, lâmpada ou controle como favorito. |

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

- imagem multiarch do backend no GHCR;
- APK universal assinado;
- Compose, configuração do MediaMTX, instalador e contrato OpenAPI;
- checksums SHA-256, SBOM, proveniência e GitHub Release.

Não é necessário criar tags nem executar comandos de release manualmente: basta fazer commit e push para `main`.

## Família, eventos e segurança da consulta

A localização Android usa Fused Location Provider. O servidor combina margem de
precisão com três observações por pelo menos dois minutos antes de avisar entrada
ou saída; leituras inconclusivas cancelam a confirmação. O celular envia amostras
adicionais enquanto necessário, mas o histórico consolida posições próximas antes de paginar. O Android envia
apenas coordenadas, precisão e horário; o backend resolve o endereço com cache.
Android e painel exibem uma linha do tempo simples, sem numeração de pontos.
Alertas incluem pessoa e área, sem notificar o próprio usuário que se deslocou.
O percurso tem pontos numerados e detalhes de horário/precisão. Veja
[localização](docs/location-tracking.md).

Regras de movimento podem limitar região da imagem, duração, sensibilidade e
horário, inclusive períodos que atravessam a meia-noite. Isso detecta movimento
na região, não identifica o bebê nem diagnostica perigo, postura ou respiração.
Veja [movimento em regiões](docs/motion-regions.md).

A sessão web usa cookie Secure, HttpOnly e SameSite=Strict, com 30 dias de validade
renovada durante o uso. Logout e troca de senha revogam a sessão; o JavaScript
não recebe a credencial. Promover alguém a administrador pelo painel pede a senha
de quem está operando. Os limites por minuto são 3000 globais / 1200 por endereço,
30 globais / 10 por endereço para autenticação, 60 para snapshots/gravações e
12 aberturas WHEP por sessão/endereço. Respostas 429 incluem Retry-After. Cabeçalhos
encaminhados não são usados como identidade: usuários de um proxy compartilham
seu orçamento. Veja [painel e segurança](docs/web-viewer.md).

## Operação e release 2.x

A release 2.0.0 mantém a API /api/v1 e migra o SQLite automaticamente, preservando
usuários, câmeras, regras e mídia. Atualize o servidor e instale também o APK novo
para receber os ajustes de localização e WebRTC. Android pede confirmação para
instalar o APK; o servidor não instala aplicativos silenciosamente.

A CI bem-sucedida em main inicia a release: a execução 56 publica 2.0.0, e as
seguintes incrementam o patch. O versionCode Android continua crescente.
O app bloqueia o uso com um sheet obrigatório quando há um APK novo e o painel
web mostra um aviso persistente; não há atualização automática. No Android, o
download do APK é iniciado no próprio sheet, e a instalação exige confirmação
do sistema. Para atualizar o servidor e o web,
execute novamente o instalador oficial. O instalador remove o antigo container
de atualização e as variáveis que ele usava; não há acesso ao Docker pelo backend.

Faça backup consistente do SQLite e preserve junto o volume valky-data
(incluindo secrets/master.key, certificados e mídia), .env, compose.yaml
e mediamtx.yml. Não copie apenas o arquivo .db de uma instância ativa sem
considerar o WAL: use backup SQLite ou pare a stack durante a cópia.
Depois de atualizar, confira docker compose ps, /health e reprodução real.
Remova imagens antigas sem uso somente após validar; não remova volumes de dados
para fazer uma atualização.

## Compatibilidade e limites

Valky apresenta apenas recursos anunciados por ONVIF/RTSP. Ausências degradam a interface sem impedir o restante da câmera. Recursos proprietários da Tapo que não fazem parte do Profile S — como áudio bidirecional, holofote, sirene, privacidade e patrulha — não são controlados pelo projeto.

Não há gravação contínua, nuvem central, cadastro público nem promessa de entrega absoluta de alarmes quando faltam energia, rede ou permissões do Android.

Veja também o [registro da revisão 2.0](docs/review-2.0.md) e a
[revisão de segurança e estabilidade 4.4](docs/security-review-4.4.md).

## Licença

Distribuído sob a [licença MIT](LICENSE). © 2026 Fernando Forastieri.

### Contas e acessibilidade

Cada pessoa possui usuário e senha próprios. O QR Code é um convite de uso único para o primeiro cadastro; acessos seguintes reutilizam a conta, inclusive em outro aparelho. Administradores controlam quem visualiza e quem edita regras e podem redefinir credenciais. Consulte [a migração das contas existentes](docs/security-access.md#migração-de-instalações-existentes) antes de atualizar uma instalação que ainda usa senha compartilhada.

O Android respeita a escala de fontes do sistema: cards e ações se reorganizam em telas estreitas ou com texto ampliado, e os sheets permitem rolagem sem esconder as ações. Há testes de interface com fonte em 200%.
