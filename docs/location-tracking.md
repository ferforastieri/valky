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

Referências:
- https://developer.android.com/develop/sensors-and-location/location/geofencing
  (permanência para reduzir alertas breves; raio mínimo recomendado de 100 m).
- https://developer.android.com/reference/android/location/Location#getAccuracy()
- https://developers.google.com/android/reference/com/google/android/gms/location/LocationRequest.Builder
