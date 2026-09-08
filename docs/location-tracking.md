# Localização e confirmação de áreas

O celular usa FusedLocationProviderClient, que integra as fontes de localização do
Android. Solicita alta precisão a cada minuto, sem distância mínima de coleta,
aguarda uma leitura melhor na inicialização e não aceita localização histórica
inicial. A precisão declarada pelo Android é uma estimativa estatística (68%),
não um limite garantido do erro.

Os envios continuam limitados por movimento (100 m ou a soma das incertezas) e
heartbeat de 15 minutos. Cada movimento/heartbeat abre quatro minutos de leituras
de confirmação. A resposta do servidor mantém essa janela aberta enquanto uma
transição ainda aguarda confirmação, inclusive em deslocamentos lentos. Os envios de confirmação não criam pontos estacionários extras:
o servidor continua filtrando o histórico separadamente.

Para cada pessoa/área, o servidor:
- descarta leituras antigas/repetidas;
- preserva a margem espacial de no mínimo 15 m ou a precisão declarada;
- considera a faixa de incerteza inconclusiva;
- exige três leituras independentes, cobrindo pelo menos dois minutos, para
  mudar de dentro para fora ou vice-versa;
- cancela a candidata quando a pessoa volta ao lado estabelecido ou a leitura
  fica inconclusiva; intervalos superiores a três minutos reiniciam a confirmação;
- persiste candidatas no SQLite, mantendo a confirmação durante reinícios.

A primeira observação estabelece presença sem alerta. Editar a geometria da área
reinicia a confirmação pela versão da área. Atrasos e falta de precisão podem
adiar alertas; não existe garantia de precisão absoluta em interiores.
Celulares precisam receber o APK novo para enviar as leituras de confirmação.

Alertas levam nomes de pessoa e área dentro do payload criptografado. O mesmo
formatador é usado nas notificações Android, na visão geral e nos eventos.

O histórico usa um critério separado: precisão declarada de até 50 m, distância
mínima de 200 m entre posições retidas e nenhuma exceção para gravar outra posição
do usuário ao confirmar uma área. Leituras próximas atualizam `lastSeenAt`. A API
consolida também os registros antigos antes de paginar, preservando os dados brutos
e visitas de retorno após um deslocamento. Os dois clientes mostram os intervalos
em uma linha do tempo vertical, com páginas de 20 itens. O parâmetro `until`,
obtido do primeiro `lastSeenAt`, mantém as páginas estáveis durante novas leituras.

O Android envia somente coordenadas, precisão e horário. A resolução de endereço
é responsabilidade do backend, em um worker separado, com nomes de áreas locais
e [Photon](https://github.com/komoot/photon), cache persistente e limite de uma
consulta a cada 10 segundos. Falhas de rede não bloqueiam o registro de localização.
A instalação pode usar uma instância própria via `VALKYRIS_GEOCODER_URL`.

Referências:
- https://developer.android.com/develop/sensors-and-location/location/geofencing
  (permanência para reduzir alertas breves; raio mínimo recomendado de 100 m).
- https://developer.android.com/reference/android/location/Location#getAccuracy()
- https://developers.google.com/android/reference/com/google/android/gms/location/LocationRequest.Builder
