# Recursos

O que o Valky faz e como usar cada parte.

## Câmeras ONVIF

- **Cadastro:** nome, ícone, IP, porta (padrão 2020), usuário e senha. O Valky consulta `GetCapabilities` e `GetProfiles`, guarda o token do perfil principal e mostra apenas os recursos que a câmera anuncia: snapshot, eventos, PTZ, zoom e áudio. Para Tapo, o RTSP principal é montado automaticamente.
- **Cadastro assíncrono:** a câmera aparece imediatamente e o progresso (`queued`, `probing`, `stream`) fica visível e persistido. Um erro terminal fica gravado na câmera, que não é removida; reiniciar o backend retoma as configurações pendentes.
- **Ao vivo:** WebRTC autenticado por WHEP. O MediaMTX mantém uma única conexão RTSP por câmera; previews da lista e snapshots saem desse mesmo stream interno.
- **PTZ:** movimento por pressionar e segurar, com zoom quando anunciado. Posições salvas ficam na própria câmera e podem ser aplicadas com um toque.
- **Posição inicial:** uma posição salva pode ser fixada como inicial; o backend a aplica cerca de 15 segundos depois de subir, dando tempo ao ONVIF e à mídia.
- **Extras:** favoritos na visão geral, estatísticas de eventos por câmera, snapshot, clipe sob demanda dos últimos 30 segundos e edição que reconfigura o stream se o endereço mudar.

## Detecção

| Tipo | Fonte | Observações |
| --- | --- | --- |
| Movimento | Eventos ONVIF ou análise visual | Câmeras sem eventos ONVIF usam comparação de quadros do stream interno. |
| Movimento persistente em região | Análise visual | Delimita uma área da imagem, com duração mínima e fração mínima de pixels alterados. |
| Pessoa | Eventos ONVIF | Depende do que a câmera anuncia. |
| Áudio | sherpa-onnx no backend | Onze tipos no catálogo: choro de bebê, choro ou soluço, grito, vidro quebrando, alarme de fumaça, alarme de incêndio, sirene, campainha, batida, latido e tosse. |

**Região de movimento.** Selecione **Movimento**, habilite **Movimento persistente em uma região** e arraste sobre a imagem para delimitar o berço ou outra área. A região usa coordenadas relativas à imagem e precisa medir pelo menos 5% da largura e da altura. O servidor analisa quadros novos do RTSP, inclusive em câmeras com eventos ONVIF; só alterações dentro da região contam e eventos genéricos ONVIF não satisfazem regras com região. A comparação usa luminância e amostragem espacial, com intervalo de cerca de 2 segundos mais o tempo de captura — não é análise contínua de todos os quadros, então movimentos entre amostras podem passar despercebidos. Uma amostra sem movimento, falha na captura ou intervalo maior que 12 segundos reinicia a contagem; mudanças em 80% ou mais da imagem inteira também reiniciam, para reduzir alertas de troca de infravermelho/exposição (isso pode omitir movimentos reais que ocupem quase toda a imagem).

**Limite por horário.** Permite selecionar dias, início, fim e fuso IANA. Segunda 22:00–06:00 inclui terça até 05:59; 06:00 está fora da janela. Sem horário, a regra vale o dia todo.

**Áudio.** A análise usa janelas de 4 segundos a cada 2 segundos, com a fila guardando só a janela mais recente. Janelas sobrepostas não contam como confirmações independentes. Para choro de bebê, a pontuação precisa ser de pelo menos 0,50 em duas janelas sem sobreposição (pelo menos 8 segundos de áudio); depois de confirmada, a evidência continua válida enquanto a pontuação ficar acima de 0,50. `Crying, sobbing` é registrado à parte e não vira choro de bebê. A tosse usa uma segunda passada em janelas de 2 segundos apenas quando a janela longa já tem evidência razoável (0,55 ou mais), o que recupera tosses curtas sem promover ruído baixo do quarto. Horários, confirmações adicionais e intervalo entre alertas continuam sendo aplicados pelo serviço de regras, e a detecção de som não classifica risco clínico.

O sistema detecta movimento na região e sons; ele não identifica o bebê, não avalia postura, respiração, sufocamento, convulsões ou perigo médico. Adultos, cobertas e outros objetos podem gerar alertas. Não substitui supervisão nem práticas de sono seguro. Mantenha a câmera fixa, desative rastreamento automático/PTZ enquanto usa regiões e marque a região de novo após mudar o enquadramento.

## Regras e alertas personalizados

Cada regra combina tipos de detecção, confirmações (para detecções que não são de áudio), cooldown de 10 a 3600 segundos (padrão 60), horário e ações:

- **Gravar:** gera snapshot e clipe do evento.
- **Notificar:** envia a notificação comum.
- **Alarme:** envia o alarme crítico, com som e tela cheia conforme a configuração.

As regras ficam sempre ativas quando habilitadas; a confiança mínima é aplicada internamente por detector, sem ajuste manual. Os destinatários podem ser toda a família (`null`), uma lista escolhida ou ninguém (`[]`), e o filtro vale no enfileiramento e na entrega. A gravação e a consulta dos eventos continuam compartilhadas: destinatário não é uma ACL de acesso ao histórico.

**Apresentação.** Em **Câmeras → Regras → Editar regra → Notificações e alarme** é possível editar título e mensagem de notificações e alarmes, escolher alarme padrão, toque padrão ou sem som, ativar vibração e permitir abertura em tela cheia. Uma prévia mostra os textos; campos vazios usam o texto traduzido do aplicativo. Títulos têm até 80 caracteres, mensagens até 240 e o total dos textos até 1600 bytes UTF-8, para caber no envelope cifrado do FCM. Cada regra tem seus próprios textos e preferências. O objeto `actions.alerts` está disponível em GET/POST/PUT de regras: omitir preserva a configuração e enviar `{}` restaura os padrões.

O som usa um serviço de reprodução em primeiro plano, com foco de áudio de alarme, e não depende da tela cheia. Se o toque do aparelho não puder ser aberto, há um som de reserva no APK. Silenciar pela tela ou pela notificação interrompe a reprodução, e confirmar o evento também. Sem interação, a reprodução termina após dois minutos, preservando a notificação. A confirmação só remove a notificação após sucesso no servidor; se o Android impedir o serviço em segundo plano, a notificação mantém seu som de sistema. Volume de alarme, permissões e restrições de fabricante continuam valendo.

## Eventos

Cada evento guarda snapshot e, quando a regra grava, um clipe com 5 segundos de pré-rolagem e 10 de pós-rolagem. Eventos sobrepostos estendem e compartilham o mesmo clipe, mantendo seus próprios snapshots. Alertas de localização levam nome de pessoa e área dentro do payload cifrado, com o mesmo formatador usado na visão geral e nas notificações.

A home do app e do painel mostra o gráfico de atividade com períodos de 12, 24, 36 ou 48 horas (12 barras de 1 a 4 horas cada), agregado no banco e atualizado a cada 15 segundos enquanto a tela está visível. Tocar em uma barra abre o intervalo, inclusive vazio, com registros paginados de 100 em 100. Marcar um evento como lido remove a notificação; **marcar todos** limpa a lista de pendentes.

## Iluminação local

O Valky controla lâmpadas Wi-Fi compatíveis com Tuya (protocolos locais 3.1 a 3.5) direto na rede de casa, na porta TCP 6668. Depois de cadastrada, é possível ligar, desligar e, quando o dispositivo oferecer, alterar brilho, temperatura do branco e cor RGB. Nada conversa com a internet: a descoberta e o controle acontecem na LAN.

O domínio de iluminação não depende de conta de fabricante. O backend expõe uma fronteira de driver local com quatro operações (descoberta, validação e capacidades, leitura de estado e aplicação de mudança); `internal/light/tuya` implementa essa fronteira com implementação própria, verificada contra quadros de referência, sem bibliotecas de terceiros.

**Cadastro:** pareie a lâmpada no aplicativo **Tuya Smart** ou **Smart Life**, obtenha o **Device ID** e a **chave local** e informe os dois em **Dispositivos → Iluminação → +** (apenas administradores). O botão **Buscar na rede** preenche o endereço e, quando a lâmpada anuncia, também o identificador. O backend valida a chave contra a lâmpada antes de gravar; se estiver errada, a mensagem explica. A chave local é fixa e só é necessária uma vez.

Para obter a chave local, a via recomendada é o utilitário do repositório, que usa o SDK oficial da Tuya com autorização por QR code (o mesmo mecanismo do Home Assistant):

```bash
python3 -m venv /tmp/tuya && /tmp/tuya/bin/pip install tuya-device-sharing-sdk cryptography qrcode
/tmp/tuya/bin/python scripts/tuya-local-key.py
```

Pegue o **User Code** no aplicativo (aba **Meu** → engrenagem → **Conta e Segurança** → **User Code**), informe quando o script pedir, aponte o celular para o QR exibido no terminal e leia a lista com Device ID, chave local e IP. O painel `iot.tuya.com` (Cloud project → Link App Account → Query Device Details) também funciona quando acessível, mas pode ficar indisponível de algumas redes.

**Descoberta de endereço:** o Valky guarda o último endereço funcional e procura de novo pelo identificador nos anúncios da rede quando a lâmpada não responde. Redes com isolamento entre clientes, VLANs ou firewall precisam permitir que o container alcance a lâmpada na porta TCP 6668; a descoberta usa UDP 6666, 6667 e 7000.

**Capacidades:** a lâmpada informa quais pontos de controle expõe; o adaptador detecta o layout e o guarda por dispositivo.

| Layout | Liga/desliga | Modo | Brilho | Branco | Cor |
| --- | --- | --- | --- | --- | --- |
| moderno | 20 | 21 | 22 (10–1000) | 23 (0–1000) | 24 (HSV em hexadecimal) |
| legado | 1 | 2 | 3 (25–255) | 4 (0–255) | 5 (RGB em hexadecimal) |
| branco | 1 | — | 2 (25–255) | 3 (0–255) | — |

Brilho é guardado em porcentagem e temperatura em Kelvin: o valor bruto do firmware nunca chega ao aplicativo, e a temperatura é mapeada linearmente entre 2700 K e 6500 K. A descoberta também pode ser conferida no servidor com `go run ./cmd/lightscan` (`-probe` testa a porta de controle).

**Segurança:** a chave local é cifrada no SQLite com a chave mestra AES-256-GCM; a API nunca devolve a chave; todos os comandos passam pelo backend autenticado; só administradores cadastram, renomeiam, removem ou mudam a configuração, e qualquer usuário autenticado opera uma lâmpada já cadastrada. A descoberta só envia tráfego para as sub-redes locais do host, nunca para a internet.

## Controle remoto de TVs

A seção **Dispositivos** reúne câmeras, iluminação e controles remotos. O backend é a ponte entre o celular e a TV: o app nunca fala direto com a TV, então o controle funciona pela mesma sessão autenticada, por LAN ou VPN. O token que a TV emite fica cifrado no SQLite e nunca é devolvido pela API.

O domínio é agnóstico de marca; o primeiro driver implementa o canal WebSocket das TVs Tizen (2016+), na porta 8002 (TLS) com fallback para 8001. Modelos antigos expõem o canal sem TLS. O controle apenas envia teclas: não altera nenhuma configuração da TV.

**Descoberta e pareamento:** ao abrir a aba, o app procura TVs sozinho na rede do celular (SSDP/UPnP e varredura da sub-rede como alternativa quando o roteador bloqueia multicast). Tocar em uma TV encontrada cadastra o controle e inicia o pareamento; a TV mostra um aviso de autorização, e ao aceitar devolve o token que o backend guarda cifrado. Depois disso os comandos não pedem mais autorização. Também é possível cadastrar manualmente (nome, IP e porta, padrão 8002). Se a TV trocar o token, basta parear de novo. Se ela trocar de endereço depois de pareada, o backend varre a faixa do último IP nas portas 8001/8002 e confirma o MAC antes de gravar o novo endereço — o mesmo caminho que traz a lâmpada de volta.

**Teclas aceitas:** `KEY_POWER`, setas, `KEY_ENTER`, `KEY_RETURN`, `KEY_HOME`, `KEY_MENU`, `KEY_EXIT`, volume, mudo, canais, `KEY_CH_LIST`, `KEY_PRECH`, reprodução (`KEY_PLAY`, `KEY_PAUSE`, `KEY_STOP`, `KEY_REWIND`, `KEY_FF`, `KEY_REC`), `KEY_INFO`, `KEY_GUIDE`, `KEY_TOOLS`, `KEY_SOURCE`, `KEY_TV`, `KEY_HDMI`, `KEY_0` a `KEY_9` e as quatro teclas coloridas. O backend rejeita qualquer comando fora dessa lista. Além das teclas, é possível digitar texto no campo da TV.

**Ligar e desligar:** o botão de energia faz as duas coisas com um toque — se a TV estiver ligada e acessível, envia `KEY_POWER`; se estiver em standby, envia um magic packet (Wake-on-LAN, UDP porta 9). O MAC é capturado automaticamente ao parear, pela interface que estiver ativa (cabo ou Wi-Fi). Na TV, ligue **Configurações → Geral → Rede → Configurações especialistas → Power On with Mobile** e deixe-a em standby (não fora da tomada). Como o backend roda em Docker, o firewall do host precisa permitir `UDP 9` para o broadcast da LAN e as bridges precisam de `bc_forwarding=1` — o instalador cria o helper e o timer que reaplicam isso, inclusive depois de uma rede recriada. Wi-Fi (WoWLAN) é bem menos confiável que cabo Ethernet: pode exigir 2–3 tentativas.

**Latência e limites:** as teclas reutilizam uma conexão WebSocket mantida aberta por TV, e o backend serializa os comandos por aparelho, então os toques saem em ordem e quase instantâneos; se a TV fechar a conexão, o backend reconecta na próxima tecla. Quando a TV não responde, o backend devolve o erro em poucos segundos e evita enfileirar toques, porque a espera longa faz o Cloudflare cortar a conexão e mostrar a página de erro dele. A TV precisa estar ligada e acessível para receber teclas. Isolar clientes ou bloquear multicast impede a descoberta automática — use o cadastro manual. Reservar o IP da TV no roteador continua recomendado, mas não é obrigatório: o backend reencontra a TV pelo MAC. O firewall do host precisa liberar TCP 8001/8002 para a faixa da TV, não só para um IP — uma regra presa ao endereço antigo derruba o controle quando o DHCP muda a TV de lugar. A Samsung removeu, a partir do Tizen 5 (2020+), a API que listava aplicativos instalados, então o controle não exibe barra de apps.

## Localização da família

O celular usa o Fused Location Provider, pede alta precisão a cada minuto, sem distância mínima de coleta, aguarda uma leitura melhor na inicialização e não aceita localização histórica inicial. A precisão declarada pelo Android é uma estimativa estatística (68%), não um limite garantido do erro.

Os envios são limitados por movimento (100 m ou a soma das incertezas) e heartbeat de 15 minutos; cada movimento ou heartbeat abre quatro minutos de leituras de confirmação, e a resposta do servidor mantém a janela aberta enquanto uma transição aguarda confirmação. Para cada pessoa/área, o servidor descarta leituras antigas, preserva a margem espacial de no mínimo 15 m ou a precisão declarada, considera a faixa de incerteza inconclusiva e exige três leituras independentes cobrindo pelo menos dois minutos para mudar de dentro para fora ou vice-versa. Voltar ao lado estabelecido ou uma leitura inconclusiva cancela a candidata; intervalos maiores que três minutos reiniciam a confirmação; candidatas são persistidas no SQLite, então a confirmação sobrevive a reinícios. A primeira observação estabelece presença sem alerta e editar a geometria da área reinicia a confirmação.

O histórico usa critério próprio: precisão de até 50 m, distância mínima de 200 m entre posições retidas e nenhuma exceção para gravar outra posição ao confirmar uma área. Android e painel mostram mapa e linha do tempo juntos, com páginas de 20 intervalos; selecionar um registro centraliza a posição no mapa e vice-versa. Linhas tracejadas indicam apenas ligações aproximadas entre leituras, não o trajeto pelas ruas. O mapa usa os tiles padrão do OpenStreetMap, gratuitos e sem chave de API.

O Android envia apenas coordenadas, precisão e horário; a resolução de endereço é responsabilidade do backend, em um worker separado, com nomes de áreas cadastradas, [Photon](https://github.com/komoot/photon), cache persistente e limite de uma consulta a cada 10 segundos. Falhas de rede não bloqueiam o registro. A instalação pode usar uma instância própria via `VALKY_GEOCODER_URL`; o provedor recebe somente coordenadas, sem identidade ou credenciais.

Alertas de entrada e saída levam nome de pessoa e área, sem notificar o próprio usuário que se deslocou (os dispositivos daquela pessoa são pulados). Um aviso de bateria baixa (20% ou menos) entra no mesmo canal, no máximo a cada 6 horas.

A permissão de localização durante o uso permite iniciar o serviço com o app visível; a permissão em segundo plano é necessária para retomada após reiniciar o telefone. Ao sair da conta, o serviço para e os marcadores locais são apagados. A tela Família recarrega ao entrar e a cada 15 segundos enquanto visível.

## Painel web

Abra `/app/` no endereço do servidor e entre com seu usuário e senha. O painel consulta câmeras (previews e vídeo WebRTC), os últimos 200 eventos e suas mídias, família e histórico, áreas, regras e informações do servidor, atualizando a cada 15 segundos enquanto a aba está visível. A interface usa Astro, TypeScript e as fontes da landing; o build separado (`pnpm build:viewer`) gera `web/dist-viewer`, que o Dockerfile inclui em `/opt/valky/web` e o backend serve em `/app/`. Não há serviço Node adicional.

A sessão usa o cookie `__Host-valky-viewer` e não cria usuários nem dispositivos. O painel permite as mutações autorizadas para a conta: gestão de usuários, convites e destinatários de regras, além de acender lâmpadas e usar o controle da TV. Reconhecimento de eventos, PTZ, localização e atualização do servidor continuam fora do navegador. Administradores também podem redefinir credenciais e ajustar permissões de regras. Sem permissão de visualização, o painel não pede nem mostra regras; com visualização apenas, os destinatários não são editáveis.

Convidar um usuário cria um QR gerado localmente no navegador, no formato `valky://pair?url=<origem do painel>&code=<código>`, com validade e uso único definidos pelo backend; também é possível copiar o link. Use o endereço HTTPS acessível pelo aparelho.

## Widgets do Android

- **Câmera:** última imagem capturada, atualizada pelo app.
- **Luzes:** todas as lâmpadas cadastradas em uma lista com rolagem, com ligar e desligar direto no widget.
- **Controle remoto:** teclas principais da TV pareada.

## Interface

O aplicativo é PT-BR e inglês, com tema claro e escuro e respeito à escala de fontes do sistema: cards e ações se reorganizam em telas estreitas ou com texto ampliado, e os sheets permitem rolagem sem esconder as ações (há testes de interface com fonte em 200%). A landing e a documentação do site também são bilíngues. O painel web é em português e tem alternância de tema.
