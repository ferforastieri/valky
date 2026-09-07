# Revisão 2.0.0

Escopo: inventário dos fontes versionados do backend, Android, web, instalador,
updater e workflows; busca de declarações sem consumidores e revisão dos fluxos
de API, mídia, estado das telas, localização, eventos, persistência e deploy.

## Simplificações aplicadas

- Mutações de regras/áreas/perfis usam a resposta para atualizar os flows;
  retiradas consultas redundantes imediatamente após salvar/remover.
- Câmeras consultam status a cada 15 segundos, além dos eventos WebSocket,
  em vez de consultar a cada dois segundos. Eventos de outro domínio não
  recarregam a lista de câmeras e suas imagens.
- WebSocket ocioso não é fechado só por passar um minuto sem evento.
- Snapshots dos eventos usam o stream interno já conectado à câmera.
- MediaMTX recebe PATCH de caminhos existentes; somente 404 inicia criação.
  A configuração global fica em mediamtx.yml, sem PATCH por câmera.
- Removidos helpers sem consumidores, a tela independente de regras sem rota,
  o header antigo sem uso, métodos Android de cadastro legado de pessoas e
  estilos de rodapé/identidade removidos da interface.
- O player usa apenas WebRTC. Android não aborta ICE com candidatos válidos
  por causa de STUN lento; o painel não oferece substituição por snapshot.
- README, documentação PT/EN da landing, OpenAPI e configuração de exemplo
  incluem painel, cookies, limites, WebRTC, localização e atualização 2.x.

## Limites preservados

Validação do servidor, permissões, proteção por cookie, rate limit, assinatura
e origem de APK, certificados, coordenadas e normalização de mídia são
fronteiras de confiança; não foram removidas para reduzir linhas.

A detecção visual quando uma câmera não oferece eventos ONVIF continua: é
compatibilidade funcional, não fallback de reprodução. Retentativas limitadas
de push/startup e proteção contra leituras imprecisas também continuam.
Endpoints legados /people e migrações SQLite permanecem para dados existentes.

Não houve necessidade de novas pastas: os módulos existentes já separam domínio,
API, persistência, mídia e apresentação. O helper ICE isolado permite testar
a espera/cancelamento sem inicializar o WebRTC nativo.
