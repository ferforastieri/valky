# Autorização e administração

A interface não concede permissões. Em cada requisição, o backend resolve o token opaco pelo hash no banco, verifica se o dispositivo e seu usuário estão ativos e lê o papel administrativo persistido. Campos como `isAdmin` e cabeçalhos enviados pelo cliente não concedem acesso. A alteração do papel vale nas próximas requisições.

## Superfícies públicas

- `/`, `/health`: saúde, sem dados pessoais.
- `/openapi.yaml`: contrato público, sem credenciais.
- `/app/`: arquivos estáticos com CSP, sem listagem de diretórios; dados vêm da API autenticada.
- `/api/v1/auth/status`: indica apenas se o primeiro administrador existe.
- `POST /api/v1/admin/bootstrap`: cria o primeiro administrador uma única vez, com transação e índice único. Uma instalação nova deve ser inicializada antes de ser exposta publicamente.
- `POST /api/v1/login`: valida a senha administrativa com bcrypt antes de emitir credencial.
- `POST /api/v1/pair`: exige convite temporário, de uso único, criado por administrador. Não aceita papel administrativo no payload.

Login, convite e bootstrap têm limite global de 30 e de 10 tentativas/minuto por conexão de origem, e corpo limitado a 8 KiB. O backend não confia em X-Forwarded-For nem CF-Connecting-IP fornecidos por clientes. Atrás do túnel, o orçamento por IP é compartilhado pelo proxy; isso impede contornar limites forjando cabeçalhos, mas pode causar bloqueio temporário de outros usuários sob ataque. O limite global da API é 3000/minuto e por origem 1200/minuto; mídia e negociação WebRTC têm limites próprios.

## Operações autenticadas

Todas as rotas registradas no mux protegido são verificadas por um teste que exige 401 sem credencial, inclusive com `isAdmin` forjado. Administradores são exigidos para gerenciar usuários, convites, cadastro/alteração/remoção de câmeras, configuração de push, retenção, atualização do servidor e envio manual de detecções. Os membros continuam compartilhando consulta de câmeras/eventos/mapa, PTZ, áreas e regras no Android, conforme o comportamento existente. Localização usa o dono do dispositivo autenticado; não aceita escolher outro usuário em `/me/location`.

`GET/PUT/DELETE /api/v1/admin/users` (com ID nos dois últimos) lista também usuários desativados, permite editar nome, acesso e papel, e revoga credenciais ao remover. O último administrador ativo não pode ser removido, desativado ou rebaixado. Criação continua pelo fluxo de convite/pareamento, evitando perfis sem dispositivo.

## Navegador

Login com `browserAdmin:true` solicita uma sessão administrativa somente depois da senha válida. O papel fica na tabela `viewer_sessions`, nunca no cookie. Sessões antigas de consulta não são promovidas automaticamente: é necessário entrar novamente. Cookie `__Host-valkyris-viewer` é Secure, HttpOnly, SameSite=Strict, com renovação e validade de 30 dias. Trocar a senha administrativa revoga sessões web.

O painel permite apenas as mutações explicitamente autorizadas: gerenciamento de usuários, destinatários por regra e convites. Requisições por cookie exigem cabeçalho customizado e validação de Origin/Sec-Fetch-Site. Não há CORS permissivo. O WebSocket usa a política padrão de mesma origem. Conexões realtime revalidam a credencial a cada 15 segundos e são encerradas após revogação ou desativação; novas requisições são bloqueadas imediatamente.

## Destinatários

`actions.recipientUserIds`: `null`/ausente mantém todos os usuários ativos; lista explícita limita os dispositivos aos usuários escolhidos; `[]` envia a ninguém. O filtro vale para notificação e alarme, no enfileiramento e na entrega. A gravação e consulta dos eventos continuam compartilhadas; destinatário não é uma ACL de acesso ao histórico. IDs desconhecidos, desativados ou duplicados são rejeitados. No painel, o administrador usa `PUT /rules/{id}/recipients`, sem alterar os outros campos.

Validação: testes de todas as rotas protegidas sem autenticação, operações administrativas com membro comum, cookie de consulta com papel forjado, origem externa, revogação, último administrador e destinatários. Esta revisão de código e testes não equivale a um teste de invasão externo.

Referências: [OWASP Authorization](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html) e [OWASP CSRF](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html).
