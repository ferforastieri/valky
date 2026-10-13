# Iluminação local

O Valkyris controla lâmpadas Wi-Fi compatíveis com Tuya (protocolos locais 3.1 a 3.5) diretamente na rede local, e configura uma lâmpada nova **sem nenhum aplicativo ou conta de fabricante**. Depois de configurada, é possível ligar, desligar e, quando o dispositivo oferecer essas capacidades, alterar brilho, temperatura do branco e cor RGB.

Nada aqui conversa com a internet: a descoberta, a configuração da lâmpada e o controle acontecem na rede de casa, com o backend falando direto com a lâmpada na porta TCP 6668.

## Arquitetura

O domínio de iluminação não depende de conta ou protocolo de um fabricante. O backend expõe uma fronteira de driver local com quatro operações:

- descoberta do dispositivo na rede;
- validação das credenciais e leitura das capacidades (pareamento);
- leitura do estado atual;
- aplicação de uma alteração de estado.

`internal/light/tuya` implementa essa fronteira para os protocolos Tuya LAN 3.1 a 3.5. O formato dos quadros e da descoberta foi documentado pela comunidade em [tinytuya](https://github.com/jasonacox/tinytuya) e [tuyapi](https://github.com/codetheweb/tuyapi) (ambos MIT); a implementação do Valkyris é própria, cobre apenas descoberta e leitura/escrita de pontos de controle, e é verificada contra quadros de referência gravados, sem depender de bibliotecas de terceiros.

O pareamento é conduzido por dois lados: o **aplicativo Android** tem o rádio Wi-Fi e envia as credenciais da rede para a lâmpada; o **backend** gera os valores de uso único, acompanha a rede e registra o dispositivo. A senha do Wi-Fi nunca chega ao servidor.

## Como configurar uma lâmpada

A configuração é **direta entre o celular e a lâmpada**, sem depender do roteador, da faixa de 2,4 GHz ou de mexer em configuração de rede:

1. Abra **Dispositivos → Iluminação** e toque em **+** (apenas administradores).
2. Deixe a lâmpada **piscando rápido**: ligue e desligue três vezes até ela entrar em modo de pareamento. Nesse estado ela cria a própria rede Wi-Fi.
3. Toque em **Configurar**. O Android pergunta se o aplicativo pode entrar na rede da lâmpada — aceite.
4. O aplicativo entra nessa rede, entrega o nome e a senha do seu Wi-Fi, e volta sozinho para a rede de casa.
5. A lâmpada conecta-se ao seu Wi-Fi, recebe a chave na ativação local (abaixo) e aparece na lista. Dê um nome e escolha o cômodo.

A senha do Wi-Fi é pedida **uma única vez** e fica guardada no aparelho, cifrada; o servidor nunca a recebe. O caminho direto exige Android 10 ou mais novo.

### Ativação local (por que existe um serviço a mais)

Uma lâmpada Tuya não aceita controle local antes de ser ativada uma vez, e a chave local é **entregue pelo servidor do fabricante** nesse momento (campo `localKey` da resposta de ativação). O Valkyris faz esse papel dentro de casa, com o serviço `activation` do `compose.yaml`:

- a lâmpada recebe a rede e a senha pelo celular e procura os endereços do fabricante;
- os redirecionamentos de DNS da casa apontam esses nomes para o servidor do Valkyris;
- o serviço responde a ativação com **a chave que o backend está usando na janela de pareamento** (lida do próprio banco), em HTTP e no canal protegido TLS-PSK que as lâmpadas usam;
- a chave roda depois de cada lâmpada configurada, então cada uma guarda a sua.

Nada sai da rede local e nenhuma conta é usada. Para os redirecionamentos, adicione no seu resolvedor de DNS (AdGuard Home, Pi-hole ou o roteador) apontando para o endereço do servidor Valkyris:

```text
*.tuyaus.com      -> <endereço do servidor Valkyris>
*.tuya.com        -> <endereço do servidor Valkyris>
stream.tuyaus.com -> <endereço real do fabricante>   # mantém as lâmpadas já existentes funcionando
```

Lâmpadas que já foram configuradas por um aplicativo do fabricante carregam o endereço antigo em cache e podem não pedir ativação de novo; nesse caso o fluxo informa que ela respondeu na rede mas recusou a chave. Lâmpadas novas (ou depois de um reset de fábrica) pedem ativação e são adotadas com a chave local.

## Descoberta de endereço

O Valkyris guarda o último endereço funcional. Quando a lâmpada não responde, ele procura de novo pelo identificador do dispositivo nos anúncios da rede local. Redes com isolamento entre clientes, VLANs ou firewall precisam permitir que o container do Valkyris alcance a lâmpada na porta TCP 6668; a descoberta usa UDP 6666, 6667 e 7000.

## Capacidades e pontos de controle

A lâmpada informa quais pontos de controle expõe; o adaptador detecta o layout e o guarda por dispositivo:

| Layout | Liga/desliga | Modo | Brilho | Branco | Cor |
| --- | --- | --- | --- | --- | --- |
| moderno | 20 | 21 | 22 (10–1000) | 23 (0–1000) | 24 (HSV em hexadecimal) |
| legado | 1 | 2 | 3 (25–255) | 4 (0–255) | 5 (RGB em hexadecimal) |
| branco | 1 | — | 2 (25–255) | 3 (0–255) | — |

Brilho é guardado em porcentagem e temperatura em Kelvin: o valor bruto do firmware nunca chega ao aplicativo. A temperatura é mapeada linearmente entre 2700 K e 6500 K.

## Verificação pelo servidor

A descoberta também pode ser conferida no servidor, sem o aplicativo:

```bash
cd backend
go run ./cmd/lightscan            # lista o que responde na rede local
go run ./cmd/lightscan -probe     # também testa a porta de controle de cada endereço
```

Uma lâmpada ainda em modo de pareamento não aparece nessa lista: ela só entra na rede depois de receber as credenciais.

## Segurança

- A chave local é cifrada no SQLite com a chave mestra AES-256-GCM da instalação.
- A API nunca devolve a chave para Android ou navegador.
- A senha do Wi-Fi sai do celular direto para a lâmpada; o backend não a recebe.
- Todos os comandos passam pelo backend autenticado; o navegador nunca acessa a lâmpada diretamente.
- Somente administradores podem configurar, renomear, remover ou disparar um pareamento. Qualquer usuário autenticado pode operar uma lâmpada já cadastrada.
- A descoberta só envia tráfego para as sub-redes locais às quais o host está conectado, e jamais para a internet.

## Compatibilidade entre gerações (pareamento AP)

O diálogo do pareamento muda de transporte conforme a geração do firmware; o
conteúdo (quadro LPV3.5 com o JSON `ssid`/`passwd`/`token`/`reg`) é o mesmo:

| Geração | Transporte |
| --- | --- |
| TuyaOS atual | TLS 1.2 com chave pré-compartilhada (PSK `123456`, identidade `psk_identity`), porta TCP 6668 |
| Geração anterior | O mesmo quadro LPV3.5 enviado cru no TCP 6668, sem TLS |
| Clássico | O quadro por datagrama UDP na porta 6667 (lâmpada e broadcast) |

Cada caminho abre uma conexão própria — o quadro cru nunca vai pelo mesmo canal
onde o TLS foi tentado. O aplicativo relata ao servidor qual caminho respondeu
(`tls-psk`, `tcp-direto`, `udp`) e o código devolvido pela lâmpada.
