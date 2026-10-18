# Instalação e manutenção

Guia para quem vai instalar e manter o Valky em um servidor próprio.

## Requisitos

- Linux `amd64` ou `arm64` com Docker Engine e Docker Compose v2.
- `curl` e `sha256sum` disponíveis (`openssl` é opcional, usado para gerar o código de instalação).
- Android 8.0 (SDK 26) ou superior.
- Câmera ONVIF Profile S com RTSP. Áudio é necessário apenas para a detecção sonora.
- Servidor, câmera e celular na mesma LAN, ou conectados por uma VPN privada.
- Para iluminação local, servidor e lâmpada precisam se alcançar pela rede doméstica.

Não encaminhe no roteador as portas RTSP/ONVIF da câmera nem as portas internas `8889` e `9997` do MediaMTX. A única porta de mídia publicada é a `8189` UDP/TCP.

## Preparar a câmera

1. Conecte a câmera ao Wi-Fi e reserve o IP dela no roteador (o cadastro aceita IP fixo).
2. Crie uma credencial própria de ONVIF/RTSP na câmera. Em uma Tapo TC40, a opção é **Camera Account** nas configurações avançadas; ela não usa a senha da conta TP-Link e é a única credencial que o Valky precisa.
3. Se quiser detecção sonora, confirme que o stream principal tem áudio.

O Valky não altera configurações nativas da câmera: ele consome o stream RTSP e usa ONVIF para capabilities, eventos e PTZ.

## Executar o instalador

No servidor, execute:

```bash
curl -fsSL https://valkyris.vercel.app/install.sh | sh
```

Para instalar uma versão específica em vez da última:

```bash
curl -fsSL https://valkyris.vercel.app/install.sh | VALKY_VERSION=v4.3.4 sh
```

### O que o instalador faz

- Verifica `curl`, `docker`, `docker compose` v2 e `sha256sum`; sem eles, para com uma mensagem clara.
- Baixa `compose.yaml`, `mediamtx.yml`, `VERSION` e `SHA256SUMS` da release, sempre por HTTPS (`--proto '=https' --proto-redir '=https'`) e com retentativas.
- Confere o SHA-256 de `compose.yaml`, `mediamtx.yml` e `VERSION` antes de usar.
- Cria ou atualiza os arquivos locais e o `.env`, preservando o que já existe.
- Roda `docker compose pull` e `docker compose up -d --remove-orphans`, o que também remove o antigo container de atualização de instalações antigas.
- Aguarda até 30 segundos o `/health` responder e imprime o endereço local e o código de instalação.

O instalador é idempotente: rodar de novo atualiza a instalação sem apagar dados.

### Onde ficam os arquivos

- `~/.local/share/valky` (ou `$VALKYRIS_HOME`, se definido): `compose.yaml`, `mediamtx.yml` e `.env`. As versões anteriores dos dois YAML ficam ao lado, com sufixo `.previous`.
- Volume `valky-data`: banco SQLite, `secrets/master.key`, certificados em `tls/` e a mídia dos eventos. Só o container da aplicação enxerga.
- Volume `recording-buffer`: buffer temporário de dez minutos em RAM (tmpfs, 384 MiB), compartilhado com o MediaMTX.

### Código de instalação

O instalador gera `VALKY_SETUP_TOKEN` (16 caracteres hexadecimais) na primeira execução e o imprime no fim. Ele é exigido apenas para criar a **primeira** conta de administrador, o que impede que quem alcançar a instalação primeiro crie a conta. Guarde-o junto com o `.env`; atualizações preservam o código existente.

## Primeiro acesso pelo app

1. Instale o APK da [última release](https://github.com/ferforastieri/valky/releases/latest).
2. Abra o app e informe o endereço HTTPS pelo qual o celular alcança o servidor: o endereço local impresso pelo instalador (`https://IP:8443`) ou o domínio do seu proxy.
3. Toque em **Continuar** para consultar o servidor, preencha nome, usuário, senha (pelo menos 12 caracteres) e o código de instalação, e crie a conta. Esse dispositivo se torna administrador.
4. Cadastre a câmera com nome, ícone, IP, usuário e senha. Para Tapo, o RTSP principal é montado automaticamente. A câmera aparece na hora e o progresso da configuração ONVIF fica visível na tela.

Outros celulares entram por convite: um administrador gera um QR de uso único (válido por 10 minutos) no app ou no painel, e a pessoa escaneia, escolhe usuário e senha e passa a usar a própria conta. O convite não cria administrador.

Se você usa Caddy ou outro proxy, informe o domínio HTTPS no app no lugar do endereço local.

## Notificações nativas com FCM

O Valky não exige aplicativo auxiliar no celular. O APK recebe mensagens de dados pelo Firebase Cloud Messaging e cria localmente a notificação ou o alarme nativo. O conteúdo do evento continua cifrado de ponta a ponta entre o backend e o dispositivo.

1. No Firebase Console, crie um projeto, adicione o aplicativo Android `com.ferforastieri.valky` e baixe `google-services.json`.
2. Codifique esse arquivo em base64 e cadastre o conteúdo no secret do GitHub `FIREBASE_ANDROID_CONFIG_BASE64`; ele é incluído no APK durante a release. Esse passo vale para quem compila a própria release: a release oficial já é publicada com a configuração. Sem o arquivo, o APK compila e funciona, mas não registra token nem recebe alertas.
3. Em **Configurações do projeto → Contas de serviço**, gere uma chave privada JSON. No primeiro sheet de alertas do app, escolha essa chave: o administrador a envia por TLS, o backend valida e cifra o JSON no SQLite. A conta nunca é retornada pela API, registrada no Git ou incluída na imagem. Ela precisa pertencer ao mesmo projeto Firebase configurado no APK.

O APK registra o token FCM automaticamente depois do login. No app, basta conceder a permissão de notificações; não existe configuração ou tentativa manual de registro. A chave de conta de serviço é privada e deve permanecer somente no servidor.

## Acesso de fora de casa

O caminho recomendado é uma VPN como Tailscale ou WireGuard: o celular passa a alcançar o servidor como se estivesse na LAN, sem publicar nada. A alternativa é publicar o HTTPS por um proxy ou túnel (Caddy, Cloudflare Tunnel e similares).

O endereço HTTPS transporta a API e a negociação WHEP, mas não a mídia WebRTC: o áudio e o vídeo usam a conexão ICE entre o cliente e o MediaMTX. Por isso o cliente precisa alcançar a porta `8189` UDP/TCP por LAN, VPN ou outra rota ICE configurada.

- O instalador grava `VALKY_WEBRTC_HOSTS` no `.env` com o endereço LAN do servidor. A configuração é preservada nas atualizações e aceita endereços adicionais separados por vírgula.
- Não use o domínio HTTP do túnel como endereço de mídia: ele resolve para o proxy, não para o MediaMTX.
- A descoberta STUN continua habilitada para tentar conexão direta pela internet. Em redes com NAT restritivo, o acesso externo requer uma rota de mídia alcançável (endereço público e encaminhamento de porta) ou um servidor TURN.
- Abrir o painel pelo túnel não comprova a conectividade do vídeo.

Valide o vídeo com `RTCPeerConnection.getStats()`: a conexão precisa estar `connected` e o contador `framesDecoded` do `inbound-rtp` de vídeo deve aumentar. Uma resposta HTTP 201 à negociação ou o evento `track` não comprovam reprodução. O painel só indica "Ao vivo" após o evento `playing`. Android e navegador usam o mesmo fluxo WHEP, com espera de ICE limitada a oito segundos; não há fallback de protocolo nem substituição por imagem.

## Atualizar

Servidor e web: execute o instalador de novo, com o mesmo comando. Ele atualiza `compose.yaml`, `mediamtx.yml`, a versão no `.env` e recria **Valky e MediaMTX juntos** (o vídeo é interrompido por alguns segundos). Não remova volumes para atualizar e não use `docker compose down -v`.

Android: o app bloqueia o uso com um sheet obrigatório quando há um APK novo e o painel web mostra um aviso persistente. O download começa no próprio sheet e a instalação exige a confirmação do sistema; não há atualização automática. O servidor não instala aplicativos silenciosamente.

Depois de atualizar, confira `docker compose ps`, o `/health` e uma reprodução real. Remova imagens antigas sem uso somente após validar.

## Backup e restauração

Faça backup consistente do SQLite e preserve junto:

- o volume `valky-data` completo (banco, `secrets/master.key`, certificados e mídia dos eventos);
- o `.env` (contém a versão, a porta, o código de instalação e os endereços de mídia);
- `compose.yaml` e `mediamtx.yml`.

Não copie apenas o arquivo `.db` de uma instância ativa sem considerar o WAL: use o backup do SQLite ou pare a stack durante a cópia.

```bash
cd ~/.local/share/valky
docker compose stop
docker run --rm -v valky_valky-data:/data -v "$PWD":/backup \
  debian:bookworm-slim tar czf /backup/valky-data-$(date +%F).tgz -C /data .
cp .env compose.yaml mediamtx.yml /backup/
docker compose start
```

Para restaurar, pare a stack, devolva o conteúdo ao volume e restaure `.env`, `compose.yaml` e `mediamtx.yml`; depois rode `docker compose up -d`. Sem o `master.key` do mesmo backup, as credenciais cifradas (câmeras, lâmpadas, TVs e conta FCM) não podem ser lidas e precisam ser cadastradas de novo.

## Diagnóstico rápido

```bash
cd ~/.local/share/valky
docker compose ps
docker compose logs --tail=100 valky
docker compose logs --tail=100 mediamtx
curl -k https://localhost:8443/health
```

- `/health` responde `{"success":true,"message":"Valky is healthy","data":{"status":"ok",...}}` quando o backend está de pé.
- O log do backend é JSON e registra requisições de API e a saúde; detalhes de erro interno (caminho, SQL, decifragem) ficam nele, nunca na resposta ao cliente.
- Uma câmera que não conecta mostra o erro persistido no cadastro, com o passo da configuração ONVIF em que parou.
- Vídeo que não abre quase sempre é a porta `8189` bloqueada: confira firewall, VLAN e o endereço anunciado em `VALKY_WEBRTC_HOSTS`.

Mais detalhes de operação, retenção e diagnóstico estão em `operacao.md`.
