# Detecção sonora contínua

O backend mantém uma sessão RTSP/TCP com o MediaMTX e decodifica áudio PCM mono, 16 kHz, float32. O FFmpeg permanece conectado durante a inferência: não há mais captura de WAV de 10 segundos seguida por processamento, espera e reconexão.

A análise usa janelas de 4 segundos a cada 2 segundos. A fila comporta apenas a janela mais recente; inferência lenta não bloqueia a captura nem cria uma fila crescente de áudio antigo. A conexão reinicia se a captura acumular mais de 8 segundos de atraso. Falhas de socket têm timeout de 15 segundos, com nova tentativa após 2 segundos. Uma reconexão invalida confirmações anteriores.

## Modelo e decisão

O classificador nativo usa o Zipformer int8 do sherpa-onnx (`2024-04-15`), incluído na imagem. Todas as 527 pontuações são consultadas; as classes do catálogo são agrupadas pelo maior valor, evitando perder evidências por um corte no top 10.

Para `baby_cry`:

- Pontuação de pelo menos 0,78 permite uma decisão imediata após a primeira janela completa.
- Pontuação de pelo menos 0,50 exige duas janelas sem sobreposição, com evidência mantida entre elas: no mínimo 8 segundos de áudio. Depois da confirmação, a evidência permanece válida enquanto a pontuação continuar acima de 0,50.
- Pontuação menor que 0,50, lacuna de análise ou nova conexão reinicia a evidência.
- `Crying, sobbing` é registrado separadamente e não é convertido em choro de bebê.

Esses valores são critérios internos, não probabilidades calibradas para o quarto. A amostra oficial de bebê (`6.wav`) foi reconhecida nas três janelas avaliadas, com pontuações de 0,961 a 0,982. Os exemplos de gato, assobio, música e risada não acionaram choro de bebê. Esse conjunto pequeno é uma regressão, não uma medição de sensibilidade em uso real. Ajustes futuros precisam comparar gravações representativas de choro e de sons sem choro do ambiente.

Horários, confirmações adicionais e intervalo entre alertas continuam sendo aplicados pelo serviço de regras. Janelas sobrepostas não contam como confirmações independentes. Os demais detectores preservam seus limiares. A detecção de som não classifica risco clínico.

## Diagnóstico

A tabela interna `audio_decisions` conserva aproximadamente 24 horas de pontuações, RMS do áudio, duração da inferência, identificação da sessão, limites da janela e motivo da decisão. A limpeza acontece a cada 5 minutos durante o processamento. Esses registros não contêm áudio bruto e não aparecem como eventos para a família.

Motivos incluem `strong_baby_cry`, `persistent_baby_cry`, `awaiting_independent_baby_evidence`, `overlapping_baby_evidence`, `below_baby_threshold`, `capture_interrupted`, `classification_failed` e `analysis_lag`. `accepted` indica aprovação pelo detector, não entrega de notificação. O log `audio rule evaluation` informa quantas regras aceitaram a detecção; eventos e fila de notificações permitem acompanhar as etapas seguintes.

```sql
SELECT window_end,
       json_extract(scores_json, '$.baby_cry') AS baby_score,
       json_extract(scores_json, '$.crying') AS crying_score,
       rms, inference_ms, accepted, reason
FROM audio_decisions
WHERE camera_id = :camera_id
ORDER BY id DESC LIMIT 60;
```

Não é possível determinar retroativamente a pontuação de uma noite anterior à instrumentação, sem gravação preservada. Áudio audível no player comprova a reprodução, mas não a classificação pelo detector.

## Validação

`go test -race ./...` verifica captura em janelas, sobreposição, reconexão, confirmação, cooldown e retenção. O teste opcional com o modelo real usa o pacote oficial extraído, validado pelo SHA-256 já fixado no Dockerfile:

```sh
VALKYRIS_AUDIO_TEST_MODEL_DIR=/caminho/sherpa-onnx-zipformer-small-audio-tagging-2024-04-15 go test ./internal/detector -run TestNativeAudioReference -v
```

Referências: [modelo e amostras oficiais](https://k2-fsa.github.io/sherpa/onnx/audio-tagging/pretrained_models.html), [áudio tagging por trecho](https://k2-fsa.github.io/sherpa/onnx/audio-tagging/index.html), [classe Baby cry no AudioSet](https://research.google.com/audioset/ontology/baby_cry_infant_cry.html).
