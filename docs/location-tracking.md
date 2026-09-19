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


## Atualização ao abrir o app e recuperar a conexão

O app inicia ou retoma o serviço ao voltar para primeiro plano e pede uma posição
nova com `getCurrentLocation`, sem reaproveitar cache antigo. Esse envio não espera
os 100 metros de movimento ou o heartbeat de 15 minutos; os critérios de precisão
e idade continuam obrigatórios. Recuperar a conexão também solicita uma leitura
nova. Uma falha de envio não avança o horário do último envio bem-sucedido, permitindo
nova tentativa. A tela de localização mostra falhas de obtenção e de envio ao
servidor, e permite tentar novamente.

A permissão de localização durante o uso permite iniciar o serviço enquanto o app
está visível. A permissão em segundo plano continua necessária para retomada após
reiniciar o telefone. Ao sair da conta, o serviço para e os marcadores locais de
último envio são apagados. Esses marcadores também são isolados por sessão.

A tela Família recarrega ao entrar e a cada 15 segundos enquanto estiver visível,
além de receber a atualização local após um envio bem-sucedido. Ter internet no
celular não comprova que a URL configurada do servidor está acessível fora de casa;
nessa situação o erro de envio agora fica visível, sem apresentar a posição antiga
como uma leitura nova.

## Mapa do histórico

Android e painel exibem mapa e linha do tempo juntos. Selecionar um registro
centraliza sua posição e precisão no mapa; selecionar um marcador destaca o
registro correspondente. “Ver tudo” volta ao enquadramento dos registros carregados.
Páginas anteriores acrescentam pontos ao mesmo mapa, mantendo a ordenação e a
paginação da API. Linhas tracejadas indicam apenas ligações aproximadas entre
leituras, não o trajeto exato pelas ruas. Intervalos superiores a 30 minutos entre
uma saída (`lastSeenAt`) e a próxima leitura permanecem separados.
