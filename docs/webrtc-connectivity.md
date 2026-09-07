# Conectividade do vídeo WebRTC

O endereço HTTPS (inclusive Cloudflare Tunnel) transporta a negociação WHEP. O áudio e o vídeo usam a conexão ICE entre navegador/aplicativo e MediaMTX.

O Compose publica a porta 8189 em UDP e TCP. O instalador configura `VALKYRIS_WEBRTC_HOSTS` no `.env` com o endereço LAN do servidor. Essa configuração é preservada nas atualizações e aceita endereços adicionais separados por vírgula. O domínio HTTP do túnel não deve ser usado como endereço de mídia: ele resolve para o proxy Cloudflare.

A descoberta STUN continua habilitada para tentar conexão direta pela internet. Em redes com NAT restritivo, o acesso externo requer uma rota de mídia alcançável (endereço público e encaminhamento de porta) ou um servidor TURN. Abrir o painel pelo túnel não comprova a conectividade do vídeo.

Mudanças em `compose.yaml` e `mediamtx.yml` devem ser aplicadas pelo instalador da release no host da instalação. O atualizador interno atualiza apenas o backend e não recria o MediaMTX.

Valide o vídeo com `RTCPeerConnection.getStats()`: a conexão precisa estar `connected` e o contador `framesDecoded` do `inbound-rtp` de vídeo deve aumentar. Uma resposta HTTP 201 à negociação ou o evento `track` não comprovam reprodução. O painel só indica “Ao vivo” após o evento `playing`.

Referência: https://mediamtx.org/docs/features/webrtc-specific-features


Android e navegador usam o mesmo fluxo WHEP. A espera por ICE é limitada a oito
segundos; se houver candidatos locais, a oferta continua mesmo com STUN pendente.
Ausência de candidatos gera erro de rede legível no Android. Não há fallback de
protocolo ou substituição por imagem.

O MediaMTX 1.15.4 retorna 400 quando se tenta adicionar um caminho existente.
A configuração usa PATCH primeiro e POST somente em 404, conforme o contrato
https://github.com/bluenviron/mediamtx/blob/v1.15.4/api/openapi.yaml.
