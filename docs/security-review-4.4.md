# Revisão de segurança e estabilidade 4.4

Esta revisão passou por todo o repositório (backend Go, app Android, painel web,
compose, instalador, workflows) procurando falhas exploráveis e caminhos de
falha na inicialização do aplicativo. O que segue é o que mudou e por quê.

## Autorização

- **Promoção a administrador pelo painel exige senha.** O cookie de consulta
  podia criar convites e alterar papéis, então um cookie roubado virava
  administrador permanente por um dispositivo novo. `PUT /admin/users/{id}`
  agora pede `actorPassword` (a senha de quem opera) sempre que a sessão do
  navegador promove alguém. Token de dispositivo e edições que mantêm o papel
  atual não pedem nada.
- **`X-Forwarded-Host`/`X-Forwarded-Proto` só valem de proxy privado.** A
  checagem de `Origin` usava o cabeçalho encaminhado mesmo quando o cliente
  falava direto com o backend, o que permitia escolher o host comparado. Agora
  a conexão precisa vir de loopback ou faixa privada; caso contrário vale
  `Host`.
- **Destinatários por regra existem de verdade.** `PUT /rules/{id}/recipients`
  estava documentado e presente no painel, mas não existia no servidor: quem o
  administrador pensava ter excluído continuava recebendo alertas. A rota foi
  implementada, o filtro entra no enfileiramento e na entrega, e uma edição
  comum da regra preserva a lista escolhida.

## Instalação

- **Código de instalação para o primeiro administrador.** O instalador gera
  `VALKY_SETUP_TOKEN` no `.env` e o imprime; `POST /admin/bootstrap` passa a
  exigi-lo quando configurado. Antes, quem alcançasse a instalação primeiro
  criava a conta de administrador.
- **A imagem não é mais baixada por redirecionamento inseguro:**
  `curl --proto-redir '=https'` nos artefatos da release.

## Containers e mídia

- **O MediaMTX não vê mais `valky-data`.** Ele montava o mesmo volume que
  guarda `valky.db`, `secrets/master.key` e os certificados; um problema no
  MediaMTX ou no ffmpeg lia a chave que decifra as credenciais das câmeras.
  Agora ele recebe apenas o buffer temporário de gravações.
- **Sub-rede fixa.** A API do MediaMTX autorizava toda a faixa `172.16/12`,
  o que dava leitura, publicação e reconfiguração de paths a qualquer container
  do host. A rede do projeto passa a ser `172.28.0.0/24` e é essa a única faixa
  autorizada além do loopback.
- **Rootfs somente leitura, `cap_drop: ALL` e `/tmp` em tmpfs** nos dois
  serviços.

## Servidor

- **Erros internos não são mais devolvidos ao cliente.** Respostas 5xx trazem
  "internal error" e o detalhe (caminho de arquivo, SQL, decifragem) fica no
  log do servidor.
- **Clipes só saem de dentro do diretório de dados**, mesmo que a linha do
  banco aponte para outro lugar.
- **HSTS** em todas as respostas e versão mínima de TLS 1.2 explícita.
- **Chave-mestra tem permissão revalidada** a cada inicialização (0600), não só
  na criação.
- **Convite de pareamento com 16 caracteres** (era 8), mantendo uso único e
  expiração.

## Dependências e pipeline

- **Go 1.26.9** na imagem e no `go.mod`: o `govulncheck` acusava 16 falhas da
  biblioteca padrão (ASN.1, `net/http`, `x/net/idna`) na 1.26.5.
- **`govulncheck` no CI** para impedir regressão.
- **Actions fixadas por SHA** em vez de tags móveis (`@v4`).

## App Android

O aplicativo fechava logo depois de abrir. Foram fechados todos os caminhos que
derrubavam o processo:

- decifragem do token de sessão com chave do AndroidKeyStore perdida
  (`SessionStore`, `PushSecretStore`) agora descarta o estado inválido;
- DataStore com arquivo corrompido (`ReplaceFileCorruptionHandler` e `catch`),
  que derrubava o `viewModelScope` iniciado com `SharingStarted.Eagerly`;
- callbacks do FCM sem `FirebaseApp` inicializado (`ValkyPushService`,
  `FcmRegistration`);
- `startForeground` em serviço acordado pelo `AlarmManager` fora do primeiro
  plano (`LocationTrackingService`, `AlarmPlaybackService`);
- URL de atualização não confiável lançando dentro de `LaunchedEffect`
  (`ApkInstaller`);
- data malformada vinda do servidor na tela inicial (`ActivityChart`);
- regras de R8 para os pontos reflexivos de osmdroid e zxing.

## Performance e tamanho

O mesmo passe procurou trabalho desnecessário. O que foi aplicado:

- o middleware de tradução bufferizava e re-serializava **toda** resposta
  (inclusive os arquivos do painel) só para tentar traduzir: agora ignora
  estáticos, sai direto quando o cliente pede inglês e só re-serializa quando
  alguma mensagem realmente mudou;
- o mapa de traduções saiu do caminho por requisição;
- índices novos para as consultas quentes (`events` por câmera e por ordem,
  fila de push, endereços pendentes, sessões);
- as regras ficaram em cache por 2 s (com invalidação em qualquer escrita e no
  disparo do cooldown), o que tira uma consulta por janela de detecção e por
  câmera a cada 2 s, e a leitura saiu de dentro do mutex global;
- autenticação: uma consulta em vez de três por requisição, e o `last_seen_at`
  é gravado no máximo a cada 5 minutos por dispositivo em vez de em toda
  requisição; a renovação do cookie só escreve quando faltam menos de 5 dias;
- assets com hash em `/app/_astro/` passam a ser imutáveis no cache;
- a imagem Docker deixa de instalar ~430 MB de dependências do ffmpeg que nunca
  são carregadas (mesa/dri, libLLVM, libz3) — só o binário e o fecho real de
  bibliotecas;
- o APK passa a incluir só `arm64-v8a` e `armeabi-v7a`: a `libjingle` do WebRTC
  para x86/x86_64 (emuladores) sozinha somava 24 MB no download;
- o log de requisição deixou de registrar cada arquivo estático do painel.

Ficou de fora, com motivo:

- o preview ao vivo na lista de câmeras abre uma sessão WebRTC e um
  `PeerConnectionFactory` por câmera; trocar por snapshot ou compartilhar a
  fábrica muda o comportamento da tela e merece uma medição em aparelho real;
- o monitor de região cria um `ffmpeg` por amostra (a cada 2 s) quando a câmera
  tem regra de região; o certo é manter um processo por câmera, como já é feito
  no áudio, e isso é um refactor do detector;
- o painel ainda busca oito endpoints por ciclo (agora a cada 30 s, com as
  permissões confirmadas a cada 5 minutos);
- os avatares e markers do mapa poderiam ser memoizados, e o gráfico de eventos
  do Android poderia reaproveitar formatadores de data.

## O que ficou de fora na segurança, de propósito


- A API é single-tenant: câmeras, eventos e localização são compartilhados por
  todos os membros autenticados, por desenho do produto. Não há ACL por
  usuário para histórico de localização.
- O token de dispositivo não expira e não há revogação individual além de
  desativar a pessoa; trocar a senha encerra os outros dispositivos.
- O driver de TV usa o protocolo do fabricante, com `wss://` de certificado
  próprio e token na URL, porque é o que o aparelho aceita.
- O AES-GCM dos segredos em repouso não usa dados autenticados adicionais, o
  que exigiria migrar o conteúdo já cifrado.
