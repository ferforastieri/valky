# Painel web de consulta

Abra `/app/` no endereço do servidor e use a senha da casa configurada no aplicativo. O painel consulta câmeras (previews na lista e vídeo WebRTC), os últimos 200 eventos e suas mídias, família e histórico de localização, áreas, regras e informações do servidor. Os dados são atualizados a cada 15 segundos enquanto a aba está visível.

A interface usa Astro, TypeScript e as fontes/estilos da landing. O build separado `pnpm build:viewer` usa `astro.viewer.config.mjs` e gera `web/dist-viewer`. O Dockerfile compila e inclui esse diretório em `/opt/valkyris/web`; o backend serve os arquivos em `/app/`. Não há serviço Node adicional. O painel é publicado pela imagem Docker da release e pelo atualizador habitual do servidor. O build padrão da landing não inclui o painel.

O login envia `readOnly: true` ao endpoint existente `/api/v1/login`. A credencial usa cookie __Host-valkyris-viewer com Secure, HttpOnly e SameSite=Strict, dura 30 dias de inatividade e é renovada durante o uso (no máximo uma vez por dia) e não cria usuários/dispositivos nem substitui tokens do mobile. O middleware permite consultas e somente as operações de negociação/encerramento WHEP necessárias à reprodução. Alterações, reconhecimento de eventos, PTZ, localização e atualização do servidor retornam 403. Sair revoga a sessão com `DELETE /api/v1/viewer-session`.

Os endpoints e a transmissão WHEP são os mesmos do mobile. O vídeo depende da conectividade WebRTC do servidor de mídia; não há troca automática nem botão de substituição do vídeo por imagem. O mapa usa Leaflet e tiles do OpenStreetMap. Nenhuma permissão de localização, câmera ou microfone é solicitada ao navegador.

Para desenvolvimento, execute `pnpm build:viewer` em `web` e inicie o backend com `VALKYRIS_WEB_DIR` apontando para o diretório absoluto de `web/dist-viewer`. Acesse o backend em `/app/`, mantendo a API na mesma origem.

O login de consulta e as requisições autenticadas por cookie exigem X-Valkyris-Viewer: 1. O segredo não é devolvido no JSON nem armazenado pelo JavaScript. A troca da senha revoga as sessões web. GET /api/v1/viewer-session restaura a sessão ao reabrir o painel.

Limites em memória por minuto: API 3000 globais e 1200 por endereço de conexão; login/bootstrap/pareamento 30 globais e 10 por endereço, compartilhados entre os três endpoints; imagens/gravações 60 por sessão e endereço; abertura WHEP 12 por sessão e endereço. Respostas 429 incluem Retry-After. O backend não confia em X-Forwarded-For nem CF-Connecting-IP enviados pelo cliente: acessos via Caddy compartilham o orçamento do proxy. Os contadores reiniciam com o processo e não substituem proteção volumétrica na borda.

A navegação mobile usa um dock flutuante de ícones com rótulos acessíveis; desktop mostra a logo e navegação lateral. O mapa enquadra as pessoas, sem afastar o zoom por áreas salvas distantes. Não há rodapé de sincronização nem data decorativa no cabeçalho.
