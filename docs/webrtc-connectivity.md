# Conectividade do vídeo WebRTC

O endereço HTTPS (inclusive Cloudflare Tunnel) transporta a negociação WHEP. O áudio e o vídeo usam a conexão ICE entre navegador/aplicativo e MediaMTX.

O Compose publica a porta 8189 em UDP e TCP. O instalador configura `VALKYRIS_WEBRTC_HOSTS` no `.env` com o endereço LAN do servidor. Essa configuração é preservada nas atualizações e aceita endereços adicionais separados por vírgula. O domínio HTTP do túnel não deve ser usado como endereço de mídia: ele resolve para o proxy Cloudflare.

A descoberta STUN continua habilitada para tentar conexão direta pela internet. Em redes com NAT restritivo, o acesso externo requer uma rota de mídia alcançável (endereço público e encaminhamento de porta) ou um servidor TURN. Abrir o painel pelo túnel não comprova a conectividade do vídeo.

Mudanças em `compose.yaml` e `mediamtx.yml` devem ser aplicadas pelo instalador da release no host da instalação. As atualizações do servidor são feitas manualmente pelo instalador.

Valide o vídeo com `RTCPeerConnection.getStats()`: a conexão precisa estar `connected` e o contador `framesDecoded` do `inbound-rtp` de vídeo deve aumentar. Uma resposta HTTP 201 à negociação ou o evento `track` não comprovam reprodução. O painel só indica “Ao vivo” após o evento `playing`.

Referência: https://mediamtx.org/docs/features/webrtc-specific-features


Android e navegador usam o mesmo fluxo WHEP. A espera por ICE é limitada a oito
segundos; se houver candidatos locais, a oferta continua mesmo com STUN pendente.
Ausência de candidatos gera erro de rede legível no Android. Não há fallback de
protocolo ou substituição por imagem.

O MediaMTX 1.15.4 retorna 400 quando se tenta adicionar um caminho existente.
A configuração usa PATCH primeiro e POST somente em 404, conforme o contrato
https://github.com/bluenviron/mediamtx/blob/v1.15.4/api/openapi.yaml.

### Compatibilidade do painel web

O painel tenta primeiro o stream original. Uma oferta recusada por formato ou uma
conexão estabelecida que não decodifica vídeo inicia uma única tentativa com
`?profile=browser`. O backend cria um caminho compartilhado por câmera com H.264
baseline, sem B-frames, até 1280×720 e 15 fps. O áudio Opus existente é reaproveitado.
O FFmpeg lê o stream local já conectado, começa sob demanda e encerra dez segundos
após o último espectador sair. A conversão aumenta o uso de CPU apenas nesse modo.
O Android continua usando o stream original.

POST, PATCH e DELETE preservam o perfil na URL de sessão devolvida pelo backend.
Cookies e tokens da aplicação não são encaminhados ao serviço de mídia.
Falha de conectividade ICE não dispara conversão: o painel informa que é necessário
verificar o acesso à porta 8189. Conversão de formato não resolve uma rota de rede
bloqueada. A indicação de vídeo ao vivo exige dimensões de vídeo decodificado,
e erros HTTP não são mais substituídos depois por uma mensagem genérica de timeout.
