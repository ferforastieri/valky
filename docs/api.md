# API HTTP

O contrato completo e versionável está em `backend/internal/api/openapi.yaml` e é servido em `/openapi.yaml`. Esta página resume autenticação, envelopes, códigos, limites e as rotas principais.

## Autenticação

| Credencial | Como funciona |
| --- | --- |
| Token de dispositivo | `Authorization: Bearer <token>`. O token é opaco, guardado no banco apenas como hash SHA-256, não expira e é obtido em `POST /login` (conta existente) ou `POST /pair` (convite). Desativar a pessoa, removê-la ou trocar a senha revoga os tokens. |
| Código de instalação | Exigido apenas em `POST /admin/bootstrap` quando o instalador definiu `VALKY_SETUP_TOKEN`: envie `setupToken` no corpo ou o cabeçalho `X-Valky-Setup-Token`. |
| Sessão de navegador | Cookie `__Host-valky-viewer` (Secure, HttpOnly, SameSite=Strict), criado por `POST /login` com `browserAdmin: true`. Dura 30 dias e é renovada durante o uso. Requisições por cookie exigem o cabeçalho `X-Valky-Viewer: 1` e origem válida. |
| Convite de pareamento | Criado por um administrador em `POST /pairing-sessions`; o código tem 16 caracteres, expira em 10 minutos e é de uso único. Não aceita papel administrativo no payload. |

O papel e as permissões vêm sempre da conta vinculada ao token; campos como `isAdmin` no corpo e cabeçalhos enviados pelo cliente não concedem acesso. `GET /viewer-session` devolve as permissões (`admin`, `viewRules`, `editRules`) e se as credenciais da conta já foram configuradas.

## Envelopes de resposta

Sucesso:

```json
{
  "success": true,
  "message": "Cameras listed",
  "data": []
}
```

Erro:

```json
{
  "success": false,
  "message": "authentication required",
  "error": "authentication required"
}
```

Toda resposta também traz os cabeçalhos `X-Valky-Success` (`true`/`false`) e `X-Valky-Message` com o mesmo texto do envelope. Respostas `5xx` trazem apenas `internal error`; o detalhe (caminho, SQL, decifragem) fica no log do servidor.

As mensagens do servidor são escritas em inglês e traduzidas para pt-BR por padrão. Envie `Accept-Language: en` para receber o texto original.

## Códigos e semântica

| Código | Significado |
| --- | --- |
| `200` | Operação concluída. |
| `201` | Recurso criado (login, pareamento, câmera, regra, evento etc.). |
| `204` | Concluído sem corpo (remoções, `DELETE /viewer-session`). |
| `400` | Corpo ou parâmetro inválido. |
| `401` | Credencial ausente, inválida ou expirada. |
| `403` | Permissão insuficiente (administrador, regras) ou operação não permitida para a sessão de consulta. |
| `404` | Recurso não encontrado. |
| `409` | Conflito, como criar o primeiro administrador quando ele já existe. |
| `429` | Limite por minuto excedido; inclui `Retry-After: 60` e `Cache-Control: no-store`. |

## Limites por minuto

Contadores em memória, reiniciados com o processo:

| Escopo | Limite |
| --- | --- |
| API inteira | 3000 globais e 1200 por endereço de conexão. |
| Login, pareamento e bootstrap | 30 globais e 10 por endereço, compartilhados entre `POST /login`, `POST /pair`, `POST /admin/bootstrap`, `POST /me/credentials` e `POST /me/password`. Corpo limitado a 8 KiB. |
| Imagens e gravações | 60 por sessão e endereço (`/snapshot` e `/recording`). |
| Negociação WHEP | 12 aberturas `POST` por sessão e endereço. |

O backend não confia em `X-Forwarded-For` nem `CF-Connecting-IP` enviados pelo cliente: acessos via proxy compartilham o orçamento do proxy. Os limites não substituem proteção volumétrica na borda.

## Canal em tempo real

`GET /api/v1/realtime` abre um WebSocket autenticado (mesma origem). O backend publica ali os passos da configuração de câmera (`queued`, `probing`, `stream`) e mudanças relevantes; os clientes filtram o que interessa. A credencial é revalidada a cada 15 segundos e a conexão é encerrada após revogação ou desativação; uma conexão ociosa não é fechada só por ficar sem eventos. A resposta de upgrade traz `X-Valky-Success: true` e `X-Valky-Message`.

## Rotas principais

Todas sob `/api/v1`. "Autenticado" = qualquer token de dispositivo válido; "Admin" = administrador; `viewRules`/`editRules` = permissões de regras da conta.

### Autenticação e sessões

| Método | Rota | Acesso | Finalidade |
| --- | --- | --- | --- |
| `GET` | `/auth/status` | Público | Indica apenas se o primeiro administrador já existe. |
| `POST` | `/admin/bootstrap` | Público + código de instalação | Cria o primeiro administrador e o dispositivo; só funciona uma vez. |
| `POST` | `/login` | Público | Autentica usuário e senha pessoais (bcrypt). |
| `POST` | `/pair` | Público + convite | Cria a conta e o dispositivo a partir de um convite de uso único. |
| `GET` | `/viewer-session` | Cookie de navegador | Lê permissões e o estado das credenciais; renova a sessão. |
| `DELETE` | `/viewer-session` | Cookie de navegador | Revoga a sessão do painel. |
| `POST` | `/pairing-sessions` | Admin | Cria um convite temporário para outro celular. |

### Câmeras e mídia

| Método | Rota | Acesso | Finalidade |
| --- | --- | --- | --- |
| `GET` | `/cameras` | Autenticado | Lista câmeras com estado de configuração e capabilities. |
| `POST` | `/cameras` | Admin | Cadastra câmera (nome, ícone, IP, porta, usuário, senha); responde na hora e conclui o ONVIF em segundo plano. |
| `PUT` | `/cameras/{id}` | Admin | Edita a câmera e reconfigura o stream se a conexão mudar. |
| `DELETE` | `/cameras/{id}` | Admin | Remove a câmera e seus caminhos de mídia. |
| `POST` | `/cameras/{id}/ptz` | Autenticado | Movimento PTZ e zoom. |
| `GET` / `POST` | `/cameras/{id}/ptz/presets` | Autenticado | Lista e salva posições PTZ na própria câmera. |
| `POST` | `/cameras/{id}/ptz/presets/{token}/goto` | Autenticado | Move para uma posição salva. |
| `DELETE` | `/cameras/{id}/ptz/presets/{token}` | Autenticado | Remove uma posição salva. |
| `PUT` | `/cameras/{id}/ptz/startup` | Autenticado | Define a posição aplicada quando a câmera inicia. |
| `GET` | `/cameras/{id}/stats` | Autenticado | Estatísticas agregadas dos eventos da câmera. |
| `GET` | `/cameras/{id}/snapshot` | Autenticado | Imagem do stream interno. |
| `GET` | `/cameras/{id}/recording` | Autenticado | Clipe sob demanda dos últimos 30 segundos do buffer. |
| `POST` / `PATCH` / `DELETE` | `/cameras/{id}/live/webrtc/whep[/{session}]` | Autenticado | Negociação WHEP, candidatos ICE (PATCH) e encerramento da sessão de vídeo. |
| `PUT` | `/favorites/{type}/{id}` | Autenticado | Marca ou desmarca câmera, lâmpada ou controle como favorito. |

### Eventos

| Método | Rota | Acesso | Finalidade |
| --- | --- | --- | --- |
| `GET` | `/events` | Autenticado | Lista os eventos recentes. |
| `GET` | `/events/activity?hours=12` | Autenticado | Agrega eventos por período para o gráfico de atividade. |
| `GET` | `/events/interval?from=&to=&offset=` | Autenticado | Página de eventos de um intervalo (início inclusivo, fim exclusivo). |
| `POST` | `/events/acknowledge-all` | Autenticado | Marca todos os eventos pendentes como lidos. |
| `GET` | `/events/{id}` | Autenticado | Detalhe do evento. |
| `POST` | `/events/{id}/acknowledge` | Autenticado | Marca um evento como lido. |
| `GET` | `/events/{id}/clip` | Autenticado | Clipe do evento (pré/pós-rolagem). |

### Regras

| Método | Rota | Acesso | Finalidade |
| --- | --- | --- | --- |
| `GET` | `/rules` | `viewRules` | Lista as regras. |
| `POST` | `/rules` | `editRules` | Cria uma regra. |
| `PUT` | `/rules/{id}` | `editRules` | Edita uma regra. |
| `DELETE` | `/rules/{id}` | `editRules` | Remove uma regra. |
| `PUT` | `/rules/{id}/recipients` | `editRules` | Define quem recebe os alertas (`recipientUserIds`; `null` = família inteira, `[]` = ninguém). |
| `GET` | `/detectors` | Autenticado | Lista os tipos de detecção disponíveis. |

### Iluminação

| Método | Rota | Acesso | Finalidade |
| --- | --- | --- | --- |
| `GET` | `/lights` | Autenticado | Lista as lâmpadas cadastradas. |
| `POST` | `/lights` | Admin | Cadastra uma lâmpada Tuya (endereço, Device ID e chave local). |
| `POST` | `/lights/discovery` | Admin | Procura lâmpadas na rede local. |
| `GET` | `/lights/{id}` | Autenticado | Estado e capacidades da lâmpada. |
| `PUT` | `/lights/{id}` | Admin | Edita nome e configuração. |
| `DELETE` | `/lights/{id}` | Admin | Remove a lâmpada. |
| `PUT` | `/lights/{id}/state` | Autenticado | Liga/desliga, cor, branco e brilho. |

### Controles remotos

| Método | Rota | Acesso | Finalidade |
| --- | --- | --- | --- |
| `GET` | `/remotes` | Autenticado | Lista os controles e o estado de pareamento. |
| `POST` | `/remotes` | Admin | Cadastra um controle (`name`, `host`, `port`). |
| `GET` | `/remotes/{id}` | Autenticado | Detalhe do controle. |
| `PUT` | `/remotes/{id}` | Admin | Edita nome, endereço, porta e estado. |
| `DELETE` | `/remotes/{id}` | Admin | Remove o controle. |
| `POST` | `/remotes/{id}/pair` | Admin | Pareia com a TV; aceite o aviso exibido nela. |
| `POST` | `/remotes/{id}/keys` | Autenticado | Envia uma tecla (`{ "key": "KEY_HOME" }`). |
| `POST` | `/remotes/{id}/text` | Autenticado | Digita um texto no campo da TV. |
| `POST` | `/remotes/{id}/wake` | Autenticado | Envia o magic packet (Wake-on-LAN). |
| `POST` | `/remotes/{id}/power` | Autenticado | Liga ou desliga conforme o estado atual. |

### Família, localização e áreas

| Método | Rota | Acesso | Finalidade |
| --- | --- | --- | --- |
| `GET` | `/users` | Autenticado | Lista os usuários da família vinculados aos dispositivos. |
| `PUT` | `/users/{id}` | Admin | Edita o perfil da família. |
| `GET` | `/users/{id}/history` | Autenticado | Histórico de localização paginado. |
| `GET` / `PUT` | `/me` | Autenticado | Lê e edita o próprio perfil. |
| `POST` | `/me/password` / `/me/credentials` | Autenticado | Define ou troca a própria senha. |
| `POST` | `/me/location` | Autenticado | Registra a localização do usuário vinculado ao dispositivo. |
| `GET` | `/places` | Autenticado | Lista as áreas. |
| `POST` / `PUT` / `DELETE` | `/places[/{id}]` | Autenticado | Cria, altera e remove áreas de alerta. |

### Administração e configurações

| Método | Rota | Acesso | Finalidade |
| --- | --- | --- | --- |
| `GET` | `/admin/users` | Admin | Lista usuários, inclusive desativados, com papel e dispositivos. |
| `PUT` | `/admin/users/{id}` | Admin | Edita nome, acesso e papel; promoção por sessão de navegador exige `actorPassword`. |
| `DELETE` | `/admin/users/{id}` | Admin | Remove a pessoa e revoga os dispositivos; o último administrador ativo é protegido. |
| `POST` | `/devices/push` | Autenticado | Registra o token FCM do aparelho. |
| `GET` | `/settings/push` | Autenticado | Informa apenas se o FCM está configurado. |
| `PUT` | `/settings/push` | Admin | Recebe `{ "serviceAccountBase64": "..." }`, valida e cifra a conta de serviço no SQLite. |
| `GET` | `/settings/retention` | Autenticado | Lê a retenção de mídia (idade e tamanho). |
| `PUT` | `/settings/retention` | Admin | Ajusta a retenção (1 a 365 dias, 1 a 1000 GB). |
| `GET` | `/settings/location-alerts` | Autenticado | Lê a apresentação dos alertas de localização e bateria. |
| `PUT` | `/settings/location-alerts` | Admin | Ajusta textos, toque, vibração e tela cheia desses alertas. |
| `GET` | `/system/update` | Autenticado | Consulta a última release estável; não executa atualização. |
| `GET` | `/realtime` | Autenticado | WebSocket de eventos em tempo real. |

## Exemplo autenticado

```bash
curl -k https://SEU_SERVIDOR:8443/api/v1/cameras \
  -H 'Authorization: Bearer SEU_TOKEN'
```
