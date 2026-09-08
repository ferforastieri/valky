# Autorização e administração

A interface não concede permissões. Em cada requisição, o backend resolve o token opaco pelo hash no banco, verifica se o dispositivo e seu usuário estão ativos e lê o papel administrativo persistido. Campos como `isAdmin` e cabeçalhos enviados pelo cliente não concedem acesso. A alteração do papel vale nas próximas requisições.

## Superfícies públicas

- `/`, `/health`: saúde, sem dados pessoais.
- `/openapi.yaml`: contrato público, sem credenciais.
- `/app/`: arquivos estáticos com CSP, sem listagem de diretórios; dados vêm da API autenticada.
- `/api/v1/auth/status`: indica apenas se o primeiro administrador existe.
- `POST /api/v1/admin/bootstrap`: cria o primeiro administrador uma única vez, com transação e índice único. Uma instalação nova deve ser inicializada antes de ser exposta publicamente.
- `POST /api/v1/login`: valida usuário e senha pessoais com bcrypt antes de emitir credencial.
- `POST /api/v1/pair`: exige convite temporário, de uso único, criado por administrador. Não aceita papel administrativo no payload.

Login, convite e bootstrap têm limite global de 30 e de 10 tentativas/minuto por conexão de origem, e corpo limitado a 8 KiB. O backend não confia em X-Forwarded-For nem CF-Connecting-IP fornecidos por clientes. Atrás do túnel, o orçamento por IP é compartilhado pelo proxy; isso impede contornar limites forjando cabeçalhos, mas pode causar bloqueio temporário de outros usuários sob ataque. O limite global da API é 3000/minuto e por origem 1200/minuto; mídia e negociação WebRTC têm limites próprios.

## Operações autenticadas

Todas as rotas registradas no mux protegido são verificadas por um teste que exige 401 sem credencial, inclusive com `isAdmin` forjado. Administradores são exigidos para gerenciar usuários, convites, cadastro/alteração/remoção de câmeras, configuração de push, retenção, atualização do servidor e envio manual de detecções. Os membros continuam compartilhando consulta de câmeras/eventos/mapa, PTZ e áreas no Android. Regras exigem permissões próprias de visualização e edição. Localização usa o dono do dispositivo autenticado; não aceita escolher outro usuário em `/me/location`.

`GET/PUT/DELETE /api/v1/admin/users` (com ID nos dois últimos) lista também usuários desativados, permite editar nome, acesso e papel, e revoga credenciais ao remover. O último administrador ativo não pode ser removido, desativado ou rebaixado. Criação continua pelo fluxo de convite/pareamento, evitando perfis sem dispositivo.

## Navegador

Login com `browserAdmin:true` solicita uma sessão de navegador após validar usuário e senha. O papel é lido da conta vinculada, nunca do payload ou cookie. Cookie `__Host-valkyris-viewer` é Secure, HttpOnly, SameSite=Strict, com renovação e validade de 30 dias. Trocar a senha pessoal revoga as outras sessões da conta.

O painel permite apenas as mutações explicitamente autorizadas: gerenciamento de usuários, destinatários por regra e convites. Requisições por cookie exigem cabeçalho customizado e validação de Origin/Sec-Fetch-Site. Não há CORS permissivo. O WebSocket usa a política padrão de mesma origem. Conexões realtime revalidam a credencial a cada 15 segundos e são encerradas após revogação ou desativação; novas requisições são bloqueadas imediatamente.

## Destinatários

`actions.recipientUserIds`: `null`/ausente mantém todos os usuários ativos; lista explícita limita os dispositivos aos usuários escolhidos; `[]` envia a ninguém. O filtro vale para notificação e alarme, no enfileiramento e na entrega. A gravação e consulta dos eventos continuam compartilhadas; destinatário não é uma ACL de acesso ao histórico. IDs desconhecidos, desativados ou duplicados são rejeitados. No painel, quem possui permissão de edição usa `PUT /rules/{id}/recipients`, sem alterar os outros campos.

Validação: testes de todas as rotas protegidas sem autenticação, operações administrativas com membro comum, cookie de consulta com papel forjado, origem externa, revogação, último administrador e destinatários. Esta revisão de código e testes não equivale a um teste de invasão externo.

Referências: [OWASP Authorization](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html) e [OWASP CSRF](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html).

## Contas individuais e permissões de regras

O login usa `username` e `password` da pessoa. `userName` é apenas o nome exibido no cadastro. O backend normaliza o identificador para minúsculas, aplica unicidade no banco e armazena somente o hash bcrypt da senha. Senhas novas exigem pelo menos 12 caracteres e no máximo 72 bytes UTF-8, limite explícito do bcrypt.

O QR Code autoriza somente o cadastro inicial com `POST /pair`; não autentica novamente uma conta existente. O código é consumido na mesma transação que cria a conta e vincula o aparelho. Colisão de usuário ou falha de cadastro não consome o convite. `POST /login` reutiliza o usuário pelo identificador único e nunca decide identidade pelo nome de exibição.

As permissões `viewRules` e `editRules` pertencem ao usuário; editar implica visualizar. Administradores têm ambas. Novos membros começam sem acesso às regras. Todos os endpoints de regras aplicam a permissão no servidor, inclusive destinatários. O campo `browserAdmin` escolhe o tipo de sessão de navegador, sem conceder privilégios. As sessões de navegador pertencem à conta e consultam seu estado e papel atuais.

### Migração de instalações existentes

Os IDs, históricos e tokens dos dispositivos existentes são preservados. O aplicativo solicita usuário e senha ao abrir uma conta ainda sem credenciais e salva-os em `POST /me/credentials`, usando a sessão existente como prova de acesso. Contas que já possuem senha precisam informar a senha atual para alterá-la. O administrador também pode configurar ou redefinir as credenciais na gestão de usuários.

As sessões antigas do painel, que usavam uma senha compartilhada e não pertenciam a uma pessoa, são invalidadas durante a migração. Antes de sair dos celulares antigos, atualize o aplicativo e configure as credenciais. O próximo acesso ao painel usará essas credenciais pessoais. Perfis antigos duplicados não são unidos automaticamente: nomes iguais não comprovam identidade.

A mudança de senha mantém a sessão que efetuou a alteração e revoga as demais. A redefinição administrativa revoga todas as sessões da pessoa. Desativação e remoção bloqueiam imediatamente novas requisições dos aparelhos e navegadores.
