# Operação

Como manter o Valky rodando bem no dia a dia: consumo, retenção, logs, diagnóstico e as capturas da landing.

## Energia e 24/7

Rodam sempre, enquanto a stack está de pé:

- o backend Go (API, regras, detectores, fila de notificações, worker de endereços e limpeza de retenção);
- o MediaMTX, com uma conexão RTSP e um FFmpeg por câmera (cópia do H.264 e áudio em Opus);
- a inferência de áudio de cada câmera com áudio habilitado, em janelas de 4 segundos a cada 2;
- os caminhos de mídia prontos no MediaMTX para as câmeras configuradas.

O buffer de vídeo fica em RAM: um único volume tmpfs (`recording-buffer`, 384 MiB) compartilhado entre Valky e MediaMTX em `/data/recordings`, com `nocopy: true` para não copiar arquivos antigos na primeira montagem. Os dois serviços precisam usar o mesmo volume; tmpfs independentes não compartilham os fragmentos.

- Dimensione o buffer pela soma dos bitrates × 600 segundos ÷ 8, com margem para variações, metadados e limpeza. 1 Mbit/s exige cerca de 72 MiB para dez minutos; o limite padrão não comporta qualquer quantidade de câmeras.
- Inclua esse consumo na memória disponível do host ou LXC. Se o volume encher, novas gravações temporárias podem falhar.
- O tmpfs pode usar swap sob pressão de memória (a opção `noswap` foi recusada no LXC de produção). Acompanhe memória e swap.
- O buffer desaparece quando o volume é desmontado ou o host reinicia; os clipes já salvos em `/data/events` permanecem. Após reiniciar, aguarde o buffer acumular vídeo antes de pedir clipes recentes.

Como reduzir consumo: remova câmeras sem uso, evite deixar o painel aberto com vídeo ao vivo (o perfil de navegador transcodifica e usa CPU só enquanto há espectador) e lembre que regras de região criam um processo de captura por amostra, a cada ~2 segundos. Acompanhe com `docker stats` e o uso do buffer após dez minutos, principalmente ao adicionar câmeras.

Validação: verifique o healthcheck do backend, os caminhos prontos no MediaMTX e a exportação de um MP4 recente. Dentro do MediaMTX, `df -h /data/recordings` deve mostrar tmpfs com limite de 384 MiB. Na instalação com uma câmera, o buffer em disco ocupava aproximadamente 86 MiB; após a mudança para RAM, vídeo e exportação foram verificados e o MediaMTX reportou zero gravações de bloco durante a observação — o que comprova a remoção do I/O de disco nessa amostra, não uma economia elétrica em watts.

## Retenção de mídia

Snapshots e clipes de eventos são apagados por idade e por tamanho:

- Padrão: 7 dias e 5 GB.
- Ajustável por administradores em `PUT /settings/retention` (ou na interface): de 1 a 365 dias e de 1 a 1000 GB.
- O limpador roda na inicialização e a cada hora, removendo primeiro os arquivos mais antigos (por data de modificação) até caber na idade e no tamanho.
- A retenção vale para os arquivos em `events/`; o buffer de dez minutos é separado e vive em RAM. Os registros de eventos no banco não são apagados por essa configuração.

Se o disco encher antes do prazo, reduza o tamanho ou a idade; se os clipes somem cedo demais, aumente os dois. Depois de mudar, confira o espaço livre e a mídia dos eventos recentes.

## Logs

```bash
cd ~/.local/share/valky
docker compose logs -f valky
docker compose logs -f mediamtx
```

- O backend escreve JSON em `stdout`. O log de requisição cobre apenas `/api/v1/` e `/health`; arquivos do painel e imagens não entram, para o registro continuar útil sem ruído.
- Erros internos (caminho de arquivo, SQL, decifragem) aparecem no log com detalhe; a resposta ao cliente traz só `internal error`.
- O log `audio rule evaluation` informa quantas regras aceitaram uma detecção de áudio. Para ver as pontuações, consulte a tabela interna `audio_decisions` (cerca de 24 horas), que guarda janela, sessão, pontuações, RMS, duração da inferência, se foi aceita e o motivo — sem áudio bruto e sem aparecer como evento para a família.
- O MediaMTX registra os caminhos, a publicação e o buffer. Os containers têm rootfs somente leitura, então não há arquivos de log dentro dos volumes.

## Diagnóstico

**Câmera.** O cadastro mostra `setupStatus`, `setupStep` e o erro persistido. Confira se o IP e a porta ONVIF (padrão 2020) respondem do servidor, se a credencial é a da câmera (não a do aplicativo do fabricante) e se o RTSP (porta 554) está acessível. Câmeras em VLAN isolada não são alcançadas pelo container.

**Stream.** No MediaMTX existem três caminhos por câmera: `camera-<id>-source` (fonte RTSP), `camera-<id>` (stream normalizado que o app usa) e `camera-<id>-browser` (perfil sob demanda do navegador). Vídeo que não abre quase sempre é a porta `8189` UDP/TCP bloqueada ou um endereço errado em `VALKY_WEBRTC_HOSTS`. Valide com `RTCPeerConnection.getStats()`: `connected` e `framesDecoded` aumentando. O painel só mostra "Ao vivo" após o evento `playing`.

**Detecção.** Regras de região analisam quadros a cada ~2 segundos; movimentos entre amostras podem passar. Teste com movimentos conhecidos, com a visão noturna real, e confirme que a câmera está fixa (PTZ automático invalida a região). Para áudio, veja as pontuações em `audio_decisions` e o motivo da decisão:

```sql
SELECT window_end,
       json_extract(scores_json, '$.baby_cry') AS baby_score,
       json_extract(scores_json, '$.crying') AS crying_score,
       rms, inference_ms, accepted, reason
FROM audio_decisions
WHERE camera_id = :camera_id
ORDER BY id DESC LIMIT 60;
```

Motivos incluem `persistent_baby_cry`, `awaiting_independent_baby_evidence`, `overlapping_baby_evidence`, `below_baby_threshold`, `capture_interrupted`, `classification_failed` e `analysis_lag`; `accepted` indica aprovação pelo detector, não entrega de notificação. Não é possível determinar retroativamente a pontuação de uma noite anterior à instrumentação, sem gravação preservada.

**Notificação.** `GET /settings/push` informa apenas se o FCM está configurado. Se o backend envia e o celular não mostra, verifique a fila `push_deliveries` (tentativas e último erro), a permissão de notificações, o canal de alertas, o volume de alarme e as restrições de execução em segundo plano do fabricante (HyperOS/MIUI são conhecidos por limitar serviços). Testes automatizados não reproduzem essas restrições; valide em aparelho real, com a tela bloqueada e desbloqueada.

## Atualização de APK

O app bloqueia o uso com um sheet obrigatório quando há um APK novo, e o painel web mostra um aviso persistente. O download começa no próprio sheet e a instalação exige a confirmação do sistema: o servidor não instala aplicativos silenciosamente e não há atualização automática. Atualizar o servidor é rodar o instalador de novo (veja `instalacao.md`); atualizar pelo app troca apenas o backend e não migra o Compose de uma instalação antiga.

## Capturas de tela da landing

As imagens do site são renderizadas pelos próprios componentes Compose do app, não são mockups. Para regerar depois de uma mudança visual:

```bash
cd mobile
./gradlew :app:recordRoborazziDebug -Pscreenshots --tests '*LandingScreenshotsTest*'
```

As imagens saem em `mobile/app/build/screenshots/` com o prefixo `landing-` e são copiadas para `web/public/screenshots/` com o nome que a landing usa:

| Gravada pelo teste | Arquivo da landing |
| --- | --- |
| `landing-overview-{light,dark}` | `overview-{light,dark}.png` |
| `landing-cameras-{light,dark}` | `cameras-{light,dark}.png` |
| `landing-events-{light,dark}` | `events-{light,dark}.png` |
| `landing-rules-{light,dark}` | `rules-{light,dark}.png` |
| `landing-settings-{light,dark}` | `settings-{light,dark}.png` |
| `landing-lista-{light,dark}` | `luz-lista-{light,dark}.png` |
| `landing-luz-{light,dark}` | `luz-{light,dark}.png` |

O teste grava as telas em PT-BR, nas variantes clara e escura, com viewport de 411 × 891 dp (1233 × 2673 px). Apenas as imagens usadas pela landing fazem parte do repositório; capturas comparativas da própria página não são versionadas. Esses testes ficam fora do CI porque baixam o runtime do Robolectric na primeira execução.
