# Segurança

O modelo de segurança do Valky e o histórico das revisões.

## Contas e senhas

- Cada pessoa tem usuário e senha próprios. O login usa `username` e `password`; `userName` é apenas o nome exibido no cadastro.
- O identificador é normalizado para minúsculas, com unicidade garantida no banco, e aceita de 3 a 40 letras sem acento, números, ponto, hífen ou sublinhado, começando por letra ou número.
- Senhas novas exigem pelo menos 12 caracteres e no máximo 72 bytes UTF-8 (limite explícito do bcrypt). Só o hash bcrypt é gravado.
- Trocar a própria senha mantém a sessão que fez a alteração e revoga as demais. A redefinição por um administrador revoga todas as sessões da pessoa.
- O QR Code autoriza apenas o cadastro inicial por convite; ele não autentica de novo uma conta existente. O código é consumido na mesma transação que cria a conta e vincula o aparelho: colisão de usuário ou falha de cadastro não consomem o convite.
- Contas que ainda não têm credenciais (migração de versões antigas) definem usuário e senha em `POST /me/credentials`, usando a sessão existente como prova de acesso. Contas que já têm senha precisam informar a senha atual para alterá-la.

## Tokens e sessões

| Credencial | Armazenamento | Validade e revogação |
| --- | --- | --- |
| Token de dispositivo | Hash SHA-256 no banco (`devices.token_hash`) | Não expira. É revogado ao desativar ou remover a pessoa e ao trocar a senha. O app guarda o token cifrado com chave do AndroidKeyStore. |
| Sessão de navegador | Hash SHA-256 em `viewer_sessions` | Cookie `__Host-valky-viewer`, Secure, HttpOnly, SameSite=Strict, 30 dias renovados durante o uso (a renovação só escreve quando faltam menos de cinco dias). `DELETE /viewer-session` revoga. |
| Convite de pareamento | Hash do código em `pairing_sessions` | 16 caracteres, uso único, expira em 10 minutos. |
| Código de instalação | `VALKY_SETUP_TOKEN` no `.env` | Usado só na criação do primeiro administrador; atualizações o preservam. |

A credencial é revalidada a cada requisição: o backend resolve o token opaco pelo hash, confere se o dispositivo e o usuário estão ativos e lê o papel persistido. O canal em tempo real revalida a credencial a cada 15 segundos e é encerrado após revogação ou desativação; novas requisições são bloqueadas imediatamente. O `last_seen_at` é gravado no máximo a cada 5 minutos por dispositivo.

## Permissões

- **Administrador (`admin`):** gerencia usuários e convites, cadastra, altera e remove câmeras, controles remotos (incluindo pareamento), configuração de push, retenção e alertas de localização. Também é exigido para configurar e controlar a configuração das lâmpadas.
- **`viewRules`:** visualiza regras.
- **`editRules`:** cria, edita e remove regras e destinatários; implica visualizar. Administradores têm ambas; novos membros começam sem acesso às regras.
- Membros comuns compartilham consulta de câmeras, eventos, mapa, PTZ, áreas e comandos de TVs e lâmpadas no Android. Localização usa o dono do dispositivo autenticado: `POST /me/location` não aceita escolher outro usuário.
- A interface não concede permissões: cada requisição é resolvida no servidor. Campos como `isAdmin` no corpo e cabeçalhos enviados pelo cliente não concedem acesso, e uma mudança de papel vale a partir das próximas requisições.
- O último administrador ativo não pode ser removido, desativado ou rebaixado. Promover alguém por uma sessão de navegador exige, além do papel de administrador, a senha de quem está operando (`actorPassword` em `PUT /admin/users/{id}`); sem isso, um cookie de consulta roubado poderia criar um convite, parear um dispositivo novo e promovê-lo sem nunca conhecer a senha. Token de dispositivo e edições que mantêm o papel atual não pedem a senha.

## Cifra em repouso

- Credenciais de câmera, URI RTSP, chave local da lâmpada, token da TV e a conta de serviço do FCM são cifrados com **AES-256-GCM** usando a chave mestra de `/data/secrets/master.key`.
- A chave mestra é criada com permissão `0600`, revalidada a cada inicialização, e fica no volume `valky-data`, que só o container da aplicação monta.
- Tokens de dispositivo, sessões de navegador e convites são gravados apenas como hash SHA-256; a API nunca devolve segredos.
- O AES-GCM não usa dados autenticados adicionais (AAD), o que exigiria migrar o conteúdo já cifrado — é um limite conhecido.

## Limites por minuto

Contadores em memória, reiniciados com o processo:

| Escopo | Limite |
| --- | --- |
| API inteira | 3000 globais e 1200 por endereço de conexão. |
| Login, convite e bootstrap | 30 globais e 10 por endereço, compartilhados entre `POST /login`, `POST /pair`, `POST /admin/bootstrap`, `POST /me/credentials` e `POST /me/password`; corpo limitado a 8 KiB. |
| Imagens e gravações | 60 por sessão e endereço. |
| Negociação WHEP | 12 aberturas por sessão e endereço. |

Respostas `429` incluem `Retry-After`. O backend não confia em `X-Forwarded-For` nem `CF-Connecting-IP` fornecidos por clientes: atrás de um proxy, o orçamento por endereço é compartilhado pelo proxy, o que impede contornar limites forjando cabeçalhos, mas pode bloquear temporariamente outros usuários sob ataque. Os contadores não substituem proteção volumétrica na borda.

## Cabeçalhos e CSRF

- Todas as respostas trazem `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer` e `Strict-Transport-Security: max-age=31536000`. O TLS mínimo é 1.2.
- Os arquivos do painel em `/app/` são servidos com CSP restritiva, `X-Robots-Tag: noindex, nofollow` e `Permissions-Policy` que nega câmera, microfone e localização.
- Requisições autenticadas por cookie exigem o cabeçalho customizado `X-Valky-Viewer: 1` e validação de `Origin`/`Sec-Fetch-Site`. `X-Forwarded-Host`/`X-Forwarded-Proto` só são considerados quando a conexão vem de loopback ou faixa privada; um cliente direto não escolhe o host comparado com o `Origin`.
- Não há CORS permissivo, e o WebSocket usa a política padrão de mesma origem. O JavaScript do painel nunca recebe a credencial (o cookie é HttpOnly).

## O que é público e o que exige sessão

Público, sem dados pessoais:

- `/` e `/health`: saúde do serviço.
- `/openapi.yaml`: contrato, sem credenciais.
- `/app/`: arquivos estáticos do painel (os dados vêm da API autenticada).
- `GET /api/v1/auth/status`: indica apenas se o primeiro administrador existe.
- `POST /api/v1/admin/bootstrap`: cria o primeiro administrador uma única vez, com transação e índice único; quando o instalador grava `VALKY_SETUP_TOKEN`, a requisição precisa enviar o mesmo código. Uma instalação nova deve ser inicializada antes de ser exposta publicamente.
- `POST /api/v1/login` e `POST /api/v1/pair`.

Todo o resto exige credencial. A matriz completa está no contrato e no teste de autorização do backend.

## Isolamento entre containers

- O MediaMTX não vê `valky-data`: um problema nele ou no FFmpeg não alcança o banco, a chave-mestra nem os certificados. Ele recebe apenas o buffer temporário.
- A API do MediaMTX autoriza somente `127.0.0.1`, `::1` e a sub-rede fixa `172.28.0.0/24` do projeto — não a faixa `172.16/12` inteira, que daria a qualquer container do host leitura, publicação e reconfiguração de caminhos.
- Os dois serviços rodam com rootfs somente leitura, `cap_drop: ALL`, `no-new-privileges` e `/tmp` em tmpfs.
- O backend não tem socket do Docker nem rota para executar atualizações do servidor. O instalador remove o antigo container de atualização (`--remove-orphans`).

## Revisões

### 2.0 — simplificação

A revisão 2.0 passou pelos fontes do backend, Android, web, instalador, updater e workflows, procurando declarações sem consumidores. Mutações passaram a usar a resposta para atualizar as telas (sem consultas redundantes), a lista de câmeras passou a atualizar a cada 15 segundos além do WebSocket, o WebSocket ocioso deixou de ser fechado por tempo, snapshots passaram a usar o stream interno, caminhos do MediaMTX passaram a usar PATCH (POST só em 404) e helpers, telas e estilos sem uso foram removidos. Validação no servidor, permissões, cookie, rate limit, assinatura de APK, certificados, coordenadas e normalização de mídia foram mantidos como fronteiras de confiança. A detecção visual quando a câmera não oferece eventos ONVIF continua (compatibilidade funcional), e endpoints legados `/people` e migrações permanecem para dados existentes.

### 4.4 — segurança e estabilidade

- **Autorização:** promoção a administrador pelo painel passou a exigir a senha de quem opera; `X-Forwarded-Host`/`X-Forwarded-Proto` só valem de proxy privado; `PUT /rules/{id}/recipients` foi implementado de verdade (estava documentado, mas não existia no servidor) e o filtro entra no enfileiramento e na entrega.
- **Instalação:** o instalador gera e imprime `VALKY_SETUP_TOKEN`, exigido no bootstrap; os artefatos da release deixaram de aceitar redirecionamento inseguro (`curl --proto-redir '=https'`).
- **Containers e mídia:** o MediaMTX deixou de ver `valky-data`; a API dele passou a autorizar só a sub-rede fixa `172.28.0.0/24`; os dois serviços ganharam rootfs somente leitura, `cap_drop: ALL` e `/tmp` em tmpfs.
- **Servidor:** erros internos não são mais devolvidos ao cliente (5xx traz `internal error` e o detalhe fica no log); clipes só saem de dentro do diretório de dados; HSTS e TLS 1.2 mínimo; a permissão da chave-mestra é revalidada a cada inicialização; o convite de pareamento passou de 8 para 16 caracteres.
- **Dependências e pipeline:** Go 1.26.10 (o `govulncheck` acusava 16 falhas da biblioteca padrão na 1.26.5), `govulncheck` no CI e Actions fixadas por SHA em vez de tags móveis.
- **App Android:** foram fechados os caminhos que derrubavam o processo — decifragem do token com chave do AndroidKeyStore perdida, DataStore corrompido, callbacks do FCM sem `FirebaseApp`, `startForeground` fora do primeiro plano, URL de atualização não confiável e regras de R8 para osmdroid e zxing.
- **Performance:** o middleware de tradução deixou de re-serializar toda resposta, índices novos para consultas quentes, regras em cache por 2 segundos, autenticação com uma consulta por requisição e `last_seen_at` a cada 5 minutos, assets com hash imutáveis no cache, imagem Docker sem ~430 MB de dependências do ffmpeg que nunca eram carregadas e APK restrito a `arm64-v8a`/`armeabi-v7a`.

Limites conhecidos, deixados de propósito:

- A API é single-tenant: câmeras, eventos e localização são compartilhados por todos os membros autenticados; não há ACL por usuário para histórico.
- O token de dispositivo não expira e não há revogação individual além de desativar a pessoa; trocar a senha encerra os outros dispositivos.
- O driver de TV usa o protocolo do fabricante, com `wss://` de certificado próprio e token na URL, porque é o que o aparelho aceita.
- O AES-GCM dos segredos em repouso não usa dados autenticados adicionais.
- Continuam em aberto, por custo/benefício: um `PeerConnectionFactory` por preview de câmera, um `ffmpeg` por amostra do monitor de região (em vez de um processo por câmera), o painel buscando oito endpoints por ciclo (a cada 30 segundos) e avatares/markers do mapa sem memoização.
