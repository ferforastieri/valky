# Buffer de vídeo em RAM

O Compose mantém o buffer temporário de dez minutos em um único volume
tmpfs, `recording-buffer`, compartilhado entre Valkyris e MediaMTX em
`/data/recordings`. Esse é o comportamento padrão da instalação.

Banco de dados, credenciais, snapshots e clipes de eventos continuam no
volume persistente `valkyris-data`, montado em `/data`. O vídeo já usa
`-c:v copy`; o FFmpeg normaliza apenas o áudio para Opus. A mudança de
armazenamento preserva os segmentos de dois segundos e a pré/pós-gravação.

## Memória e durabilidade

O volume tem limite de **384 MiB**, alocado conforme o uso. `nocopy: true`
impede que arquivos antigos sejam copiados para o tmpfs na primeira montagem.
Os dois serviços precisam usar o mesmo volume; mounts tmpfs independentes
não compartilham os fragmentos.

Dimensione o buffer pela soma dos bitrates das câmeras multiplicada por
600 segundos e dividida por 8, com margem para variações, metadados e limpeza.
Por exemplo, 1 Mbit/s exige aproximadamente 72 MiB para dez minutos antes
da margem. O limite padrão não comporta qualquer quantidade de câmeras.
Inclua esse consumo na memória disponível do host ou LXC. Se o volume
encher, novas gravações temporárias podem falhar.

O buffer pode desaparecer quando o volume for desmontado ou o host reiniciar.
Os clipes já salvos em `/data/events` permanecem em disco. Após uma reinicialização,
aguarde o buffer acumular vídeo antes de solicitar clipes recentes.

O tmpfs pode utilizar swap sob pressão de memória. Não usamos `noswap`
porque essa opção foi recusada no LXC de produção. Acompanhe memória e swap.

## Instalação e atualização

O volume está definido no próprio `compose.yaml`, distribuído na release
e usado tanto pelo instalador quanto pelo atualizador. Não exige override,
variável de ativação ou edição manual dos arquivos instalados.

Em instalações existentes, aplique a release pelo instalador no host para
atualizar o Compose e recriar **Valkyris e MediaMTX juntos**. A atualização
pelo app troca apenas o backend; ela não migra o Compose de uma instalação
antiga. Após a migração, atualizações pelo app preservam o volume compartilhado.

A recriação interrompe brevemente o vídeo. Arquivos do buffer antigo em disco
não são copiados nem apagados automaticamente. Não remova `valkyris-data`
e não use `docker compose down -v`: esse volume contém dados persistentes.

## Validação

Verifique o healthcheck do backend, os paths prontos no MediaMTX e a exportação
de um MP4 recente. Dentro do MediaMTX, `df -h /data/recordings` deve mostrar
tmpfs com limite de 384 MiB. Acompanhe `docker stats` e o uso do buffer após
dez minutos, especialmente ao adicionar câmeras.

Na instalação com uma câmera, o buffer anterior ocupava aproximadamente
86 MiB. Após a mudança, vídeo e exportação MP4 foram verificados; o MediaMTX
reportou zero gravações de bloco durante a observação. Isso comprova a remoção
do I/O de disco do buffer nessa amostra, não uma economia elétrica em watts.
