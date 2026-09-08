# Painel web de consulta

Abra `/app/` no endereço do servidor e use a senha da casa configurada no aplicativo. O painel consulta câmeras (previews na lista e vídeo WebRTC), os últimos 200 eventos e suas mídias, família e histórico de localização, áreas, regras e informações do servidor. Os dados são atualizados a cada 15 segundos enquanto a aba está visível.

A interface usa Astro, TypeScript e as fontes/estilos da landing. O build separado `pnpm build:viewer` usa `astro.viewer.config.mjs` e gera `web/dist-viewer`. O Dockerfile compila e inclui esse diretório em `/opt/valkyris/web`; o backend serve os arquivos em `/app/`. Não há serviço Node adicional. O painel é publicado pela imagem Docker da release e pelo atualizador habitual do servidor. O build padrão da landing não inclui o painel.

O login envia `readOnly: true` ao endpoint existente `/api/v1/login`. A credencial usa cookie __Host-valkyris-viewer com Secure, HttpOnly e SameSite=Strict, dura 30 dias de inatividade e é renovada durante o uso (no máximo uma vez por dia) e não cria usuários/dispositivos nem substitui tokens do mobile. O middleware permite consultas e somente as operações de negociação/encerramento WHEP necessárias à reprodução. Alterações, reconhecimento de eventos, PTZ, localização e atualização do servidor retornam 403. Sair revoga a sessão com `DELETE /api/v1/viewer-session`.

Os endpoints e a transmissão WHEP são os mesmos do mobile. O vídeo depende da conectividade WebRTC do servidor de mídia; não há troca automática nem botão de substituição do vídeo por imagem. O mapa usa Leaflet e tiles do OpenStreetMap. Nenhuma permissão de localização, câmera ou microfone é solicitada ao navegador.

Para desenvolvimento, execute `pnpm build:viewer` em `web` e inicie o backend com `VALKYRIS_WEB_DIR` apontando para o diretório absoluto de `web/dist-viewer`. Acesse o backend em `/app/`, mantendo a API na mesma origem.

O login de consulta e as requisições autenticadas por cookie exigem X-Valkyris-Viewer: 1. O segredo não é devolvido no JSON nem armazenado pelo JavaScript. A troca da senha revoga as sessões web. GET /api/v1/viewer-session restaura a sessão ao reabrir o painel.

Limites em memória por minuto: API 3000 globais e 1200 por endereço de conexão; login/bootstrap/pareamento 30 globais e 10 por endereço, compartilhados entre os três endpoints; imagens/gravações 60 por sessão e endereço; abertura WHEP 12 por sessão e endereço. Respostas 429 incluem Retry-After. O backend não confia em X-Forwarded-For nem CF-Connecting-IP enviados pelo cliente: acessos via Caddy compartilham o orçamento do proxy. Os contadores reiniciam com o processo e não substituem proteção volumétrica na borda.

A navegação mobile usa um dock flutuante de ícones com rótulos acessíveis; desktop mostra a logo e navegação lateral. O mapa enquadra as pessoas, sem afastar o zoom por áreas salvas distantes. Não há rodapé de sincronização nem data decorativa no cabeçalho.

### Gráfico de atividade

A home do painel e do Android mostram o gráfico abaixo dos cards, com períodos de 12, 24, 36 ou 48 horas. As 12 barras representam intervalos de 1, 2, 3 ou 4 horas. `GET /events/activity?hours=12` agrega os eventos que não são de localização diretamente no banco, sem o limite da lista recente. O mesmo filtro é aplicado no backend antes da paginação dos detalhes; os clientes não filtram os registros. O horário de referência vem do servidor; os clientes exibem os limites no fuso local e atualizam a atividade a cada 15 segundos enquanto a tela está visível.

No Android, tocar em uma barra abre o sheet do intervalo, inclusive quando vazio. Os registros são consultados em páginas de 100 por `GET /events/interval?from=...&to=...&offset=0`, com início inclusivo e fim exclusivo. Cada registro abre o detalhe do evento.

### Linha do tempo de localização

Android e painel mostram uma linha do tempo vertical com horário, último registro próximo e endereço, sem numeração de pontos ou seletor de 200 posições. As páginas de 20 intervalos vêm de `/users/{id}/history?limit=20&offset=0`; filtragem de precisão e consolidação espacial acontecem antes da paginação, no backend. Registros históricos brutos são preservados. Apenas observações consecutivas próximas são reunidas; sair e voltar continua sendo outro intervalo.

O telefone envia latitude, longitude, precisão e horário, usando um DTO sem endereço. O servidor ignora endereços enviados por versões antigas. O histórico novo exige precisão de até 50 m e deslocamento de pelo menos 200 m em relação à posição retida; confirmações de geofence continuam independentes e não criam pontos redundantes. Observações próximas estendem `lastSeenAt`.

A resolução de endereço roda fora da requisição de localização: nomes de áreas cadastradas têm prioridade, depois [Photon](https://github.com/komoot/photon). Um único worker limita consultas a uma a cada 10 segundos, com cache persistente por coordenada arredondada e espera de uma hora após falha. `VALKYRIS_GEOCODER_URL` pode apontar para uma instância Photon própria. O provedor recebe somente coordenadas, sem identidade ou credenciais. Se estiver indisponível, a localização continua funcionando e a interface mostra as coordenadas até o endereço estar disponível. Dados geográficos: © OpenStreetMap.

No mapa Android, tocar no avatar mostra um indicador ancorado acima dele, com nome e horário. Tocar novamente ou no mapa fecha o indicador; o histórico continua acessível na lista da família.
