# Arquitetura

Como o Valky funciona por dentro: componentes, fluxos, volumes, dados e onde ficam os limites de confiança.

## Componentes

| Componente | Papel |
| --- | --- |
| Backend Go | Único processo de aplicação. Fala HTTPS em `:8443`, serve a API, o painel em `/app/` e o contrato em `/openapi.yaml`. Concentra domínio, autenticação, regras, persistência e integração com os demais serviços. |
| MediaMTX 1.15.4 | Servidor de mídia interno. Puxa o RTSP da câmera, publica o stream normalizado, negocia WHEP/WebRTC e grava o buffer rolante. API em `:9997`, RTSP em `:8554`, WebRTC/WHEP em `:8889` e reprodução em `:9996`, todos internos. |
| FFmpeg | Roda dentro do MediaMTX (transcodificação do áudio para Opus e perfil de navegador) e dentro da imagem do backend (snapshots). |
| Detectores | Eventos ONVIF da câmera, movimento visual em região e áudio contínuo com o Zipformer int8 do sherpa-onnx (`2024-04-15`), incluído na imagem. |
| App Android | Cliente principal: câmeras, regras, eventos, luzes, TVs, localização, widgets, notificações e alarmes. |
| Painel web | Consulta servida pelo backend em `/app/`: câmeras, últimos 200 eventos, família, áreas, regras e informações do servidor. |

## Fluxo de uma detecção até o alerta

1. A câmera e as credenciais cifradas são persistidas imediatamente com o estado `pending`, então o app já pode abrir a câmera sem esperar o ONVIF.
2. O backend publica os passos `queued`, `probing` e `stream` no canal em tempo real e também os grava no SQLite. O probe ONVIF guarda capabilities, endereços de serviço e o token do perfil de mídia. Um erro terminal é sanitizado e persistido na câmera, que não é removida.
3. Eventos ONVIF e os detectores locais entregam detecções normalizadas ao motor de regras.
4. Regras que casam criam um evento, materializam um clipe de 5 segundos de pré-rolagem e 10 de pós-rolagem e enfileiram entregas por dispositivo. Eventos sobrepostos estendem e compartilham o mesmo clipe, mantendo cada snapshot.
5. O Android recebe um sinal mínimo cifrado e busca o evento autenticado pela LAN ou VPN.
6. Ao retomar, o app compara as versões do servidor e do cliente com a última release estável do GitHub por um endpoint de leitura. O Android bloqueia com um sheet obrigatório a cada APK novo; a web mostra um aviso persistente. O download do APK começa no sheet e a instalação exige confirmação do sistema. Atualização do servidor é manual, pelo instalador, nunca pela API.

Detalhes da detecção sonora:

- O backend mantém uma sessão RTSP/TCP com o MediaMTX e decodifica áudio PCM mono, 16 kHz, float32. O FFmpeg permanece conectado durante a inferência; não existe mais captura de WAV seguida de espera e reconexão.
- A análise usa janelas de 4 segundos a cada 2 segundos. A fila comporta apenas a janela mais recente: inferência lenta não bloqueia a captura nem cria fila de áudio antigo. A conexão reinicia se a captura acumular mais de 8 segundos de atraso; falhas de socket têm timeout de 15 segundos, com nova tentativa após 2. Uma reconexão invalida confirmações anteriores.
- Todas as 527 pontuações do classificador são consultadas; as classes do catálogo são agrupadas pelo maior valor.
- A tosse é um transiente curto e fica diluída na janela de 4 s. Quando a pontuação fica entre 0,05 e 0,55, o backend reclassifica janelas de 2 s deslizando a cada 0,5 s e aceita a tosse se a melhor delas chegar a 0,93. O caminho silencioso continua fazendo uma única inferência; a segunda passada só roda em cerca de 0,1% das janelas.
- Para `baby_cry`, uma pontuação de pelo menos 0,50 exige duas janelas sem sobreposição, com pelo menos 8 segundos de áudio. Depois da confirmação, a evidência continua válida enquanto a pontuação ficar acima de 0,50. Pontuação menor, lacuna de análise ou nova conexão reiniciam a evidência. `Crying, sobbing` é registrado separadamente e não vira choro de bebê.
- Esses valores são critérios internos, não probabilidades calibradas. A amostra oficial de bebê produz 0,961 a 0,982 e só confirma após duas janelas independentes; gato, assobio, música e risada não acionaram choro de bebê nesse conjunto pequeno. Ajustes futuros precisam comparar gravações representativas de choro e de sons sem choro do ambiente.
- A tabela interna `audio_decisions` conserva cerca de 24 horas de pontuações, RMS, duração da inferência, sessão, limites da janela e motivo da decisão, sem áudio bruto e sem virar evento para a família. Motivos incluem `persistent_baby_cry`, `awaiting_independent_baby_evidence`, `overlapping_baby_evidence`, `below_baby_threshold`, `capture_interrupted`, `classification_failed` e `analysis_lag`.
- Um falso alerta registrado em 08/09/2026 (janela sobreposta com 0,7159 seguida de 0,8609 e queda para 0,0019) veio do antigo caminho imediato de confiança alta, que aceitava picos sem evidência independente. Agora todos os scores, inclusive altos, exigem confirmação temporal; a sequência está coberta por teste e não dispara com o critério atual.

## Fluxo de mídia

- Uma única conexão RTSP por câmera: o MediaMTX puxa o perfil selecionado no caminho `camera-<id>-source`; um FFmpeg interno copia o H.264 sem recodificar e converte o áudio da câmera (como G.711) para Opus, republicando o caminho `camera-<id>`, que é o único stream voltado ao app.
- A negociação WHEP é autenticada pelo Go. Depois que o ICE conecta, a mídia flui direto entre o MediaMTX e o Android ou o navegador.
- Snapshots leem esse stream interno; não abrem outra sessão RTSP na câmera física.
- O buffer rolante grava fragmentos fMP4 (partes de 1 s, segmentos de 2 s, descarte após 10 min) no diretório de gravações. O clipe do evento junta os fragmentos que cobrem a janela de -5 s a +10 s. O clipe sob demanda usa o servidor de reprodução do MediaMTX para entregar os 30 segundos anteriores ao toque; se o buffer tiver acabado de reiniciar, ele devolve o trecho disponível em vez de falhar.
- O painel tenta primeiro o stream original. Se a oferta for recusada por formato, ou a conexão não decodificar vídeo, há uma única tentativa com `?profile=browser`: um caminho compartilhado por câmera com H.264 baseline, sem B-frames, até 1280×720 e 15 fps, iniciado sob demanda e encerrado dez segundos após o último espectador sair. O áudio Opus existente é reaproveitado e o Android continua no stream original.
- A configuração de mídia vive no `mediamtx.yml`. O cadastro de câmera faz PATCH de caminhos existentes e só cria quando ausente (o MediaMTX 1.15.4 responde 400 ao adicionar um caminho que já existe); a configuração global não é alterada por câmera.
- O player usa apenas WebRTC: uma espera limitada de oito segundos pelos candidatos ICE e nenhum fallback para snapshot ou HLS.

## Volumes

| Volume | Onde | O que guarda |
| --- | --- | --- |
| `valky-data` | `/data` no container da aplicação | `valky.db` (com WAL), `secrets/master.key`, certificados em `tls/` e a mídia dos eventos em `events/<id>/snapshot.jpg` e `clip.mp4`. Só o backend monta este volume. |
| `recording-buffer` | `/data/recordings` nos dois containers | Buffer temporário de dez minutos em tmpfs, limite de 384 MiB, `nocopy: true`. Compartilhado entre Valky e MediaMTX porque os dois precisam dos mesmos fragmentos. |

Sobre o buffer:

- Dimensione pela soma dos bitrates das câmeras × 600 segundos ÷ 8, com margem para variações, metadados e limpeza. 1 Mbit/s exige aproximadamente 72 MiB para dez minutos; o limite padrão não comporta qualquer quantidade de câmeras.
- O tmpfs pode usar swap sob pressão de memória (a opção `noswap` foi recusada no LXC de produção). Acompanhe memória e swap.
- O buffer desaparece quando o volume é desmontado ou o host reinicia. Os clipes já salvos em `/data/events` permanecem em disco. Após reiniciar, aguarde o buffer acumular vídeo antes de pedir clipes recentes.
- Nunca use `docker compose down -v`: isso apaga `valky-data`.

## Modelo de dados

SQLite com WAL, chaves estrangeiras ligadas e uma única conexão. Tabelas principais:

- `settings`, `cameras`, `lights`, `remotes` — configuração e credenciais cifradas dos dispositivos.
- `rules`, `events`, `push_deliveries` — regras, eventos com snapshot/clipe e fila de notificações por dispositivo.
- `users`, `devices`, `viewer_sessions`, `pairing_sessions` — contas, tokens de dispositivo, sessões de navegador e convites.
- `user_locations`, `places`, `user_place_memberships`, `geofence_candidates`, `location_address_cache` — localização, áreas, confirmação de entrada/saída e cache de endereços.
- `audio_decisions` — diagnóstico das decisões do detector de áudio.

As migrações rodam automaticamente em `store.Open`: o esquema (`schema.sql`) é aplicado de forma idempotente, colunas novas entram por `ALTER TABLE` e tabelas legadas de rastreamento são removidas. Nunca edite uma migração publicada; adicione uma nova. Os endpoints legados `/people` continuam por compatibilidade, mas os clientes atuais usam `/users`.

## Limites de confiança

- O processo Go é a fronteira de segurança: o app nunca recebe a senha da câmera e as APIs internas do MediaMTX não são expostas.
- O MediaMTX é isolado: não vê `valky-data`, roda com rootfs somente leitura, `cap_drop: ALL` e `/tmp` em tmpfs; sua API aceita apenas loopback e a sub-rede fixa do projeto (`172.28.0.0/24`), não a faixa `172.16/12` inteira.
- A rede do Compose é fixa (`172.28.0.0/24`) e apenas a porta `8189` UDP/TCP é publicada.
- Validação no servidor, validação de caminho de mídia, autenticação de sessão e migrações de banco são fronteiras intencionais; não as remova para reduzir código.
- O backend não tem socket do Docker nem endpoint para executar atualizações do servidor. O instalador remove o antigo container de atualização com `--remove-orphans`.

## Decisões de projeto

- **Single-tenant:** câmeras, eventos e localização são compartilhados por todos os membros autenticados. Não existe ACL por usuário para o histórico; é o desenho do produto.
- **Sem nuvem:** nada de mídia ou evento sai da instalação. O geocoder é a única consulta externa opcional e recebe apenas coordenadas.
- **Sem cadastro público:** o primeiro dispositivo cria o administrador (exigindo o código de instalação quando configurado) e os demais entram por convite de uso único.
- **Orientado a capabilities:** o Valky mostra apenas o que a câmera anuncia por ONVIF/RTSP. Ausências degradam a interface sem impedir o resto.
- **Sem gravação contínua:** só existe buffer curto em RAM e clipes de evento.
- **Sem promessa de entrega absoluta:** energia, rede ou permissões do Android podem impedir um alarme; o sistema registra tentativas, mas não garante entrega.
- **Atualização explícita:** servidor pelo instalador, app por sheet obrigatório; nada se atualiza sozinho.
