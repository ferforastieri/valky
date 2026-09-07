# Painel web de consulta

Abra `/app/` no endereço do servidor e use a senha da casa configurada no aplicativo. O painel consulta câmeras (imagem e vídeo WebRTC), os últimos 200 eventos e suas mídias, família e histórico de localização, áreas, regras e informações do servidor. Os dados são atualizados a cada 15 segundos enquanto a aba está visível; o botão de atualização permite consultar novamente.

A interface usa Astro, TypeScript e as fontes/estilos da landing. O build separado `pnpm build:viewer` usa `astro.viewer.config.mjs` e gera `web/dist-viewer`. O Dockerfile compila e inclui esse diretório em `/opt/valkyris/web`; o backend serve os arquivos em `/app/`. Não há serviço Node adicional. O painel é publicado pela imagem Docker da release e pelo atualizador habitual do servidor. O build padrão da landing não inclui o painel.

O login envia `readOnly: true` ao endpoint existente `/api/v1/login`. A credencial temporária dura 12 horas, fica no sessionStorage da aba e não cria usuários/dispositivos nem substitui tokens do mobile. O middleware permite consultas e somente as operações de negociação/encerramento WHEP necessárias à reprodução. Alterações, reconhecimento de eventos, PTZ, localização e atualização do servidor retornam 403. Sair revoga a sessão com `DELETE /api/v1/viewer-session`.

Os endpoints e a transmissão WHEP são os mesmos do mobile. O vídeo depende da conectividade WebRTC do servidor de mídia; há consulta da imagem quando o vídeo não conecta. O mapa usa Leaflet e tiles do OpenStreetMap. Nenhuma permissão de localização, câmera ou microfone é solicitada ao navegador.

Para desenvolvimento, execute `pnpm build:viewer` em `web` e inicie o backend com `VALKYRIS_WEB_DIR` apontando para o diretório absoluto de `web/dist-viewer`. Acesse o backend em `/app/`, mantendo a API na mesma origem.
