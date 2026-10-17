# Iluminação local

O Valk controla lâmpadas Wi-Fi compatíveis com Tuya (protocolos locais 3.1 a 3.5) diretamente na rede de casa, na porta TCP 6668. Depois de cadastrada, é possível ligar, desligar e, quando o dispositivo oferecer essas capacidades, alterar brilho, temperatura do branco e cor RGB.

Nada aqui conversa com a internet: a descoberta e o controle acontecem na rede local, com o backend falando direto com a lâmpada.

## Arquitetura

O domínio de iluminação não depende de conta ou protocolo de um fabricante. O backend expõe uma fronteira de driver local com quatro operações:

- descoberta do dispositivo na rede;
- validação das credenciais e leitura das capacidades (cadastro);
- leitura do estado atual;
- aplicação de uma alteração de estado.

`internal/light/tuya` implementa essa fronteira para os protocolos Tuya LAN 3.1 a 3.5. O formato dos quadros e da descoberta foi documentado pela comunidade em [tinytuya](https://github.com/jasonacox/tinytuya) e [tuyapi](https://github.com/codetheweb/tuyapi) (ambos MIT); a implementação do Valk é própria, cobre apenas descoberta e leitura/escrita de pontos de controle, e é verificada contra quadros de referência gravados, sem depender de bibliotecas de terceiros.

## Como cadastrar uma lâmpada

O cadastro adota uma lâmpada que **já está configurada no aplicativo da Tuya** (Tuya Smart ou Smart Life). Não é preciso parear de novo, nem mexer na configuração de rede: a lâmpada só precisa estar ligada e acessível na rede de casa.

1. Pareie a lâmpada no aplicativo **Tuya Smart** ou **Smart Life**, como de costume.
2. Obtenha o **identificador (Device ID)** e a **chave local (local key)** — veja abaixo.
3. Abra **Dispositivos → Iluminação** e toque em **+** (apenas administradores).
4. Informe o nome (opcional), o endereço na rede, o Device ID e a chave local. O botão **Buscar na rede** preenche o endereço e, quando a lâmpada anuncia, também o identificador.
5. Toque em **Salvar**. O backend valida a chave contra a lâmpada antes de gravar; se a chave estiver errada, a mensagem explica o motivo.

Depois disso o controle é local e não depende de nuvem. A chave local é fixa: não muda com o tempo.

### De onde vêm o Device ID e a chave local

A chave local é entregue pelo servidor do fabricante quando a lâmpada é
configurada, e fica vinculada à conta. Você só precisa dela **uma vez**; o
Valk não guarda a conta da Tuya nem faz login no fabricante.

A lâmpada precisa estar pareada em um aplicativo **Tuya Smart** ou **Smart Life**
(os dois usam a mesma conta Tuya). O aplicativo da marca própria não serve para
este passo: a autorização por QR code só funciona com as contas do Tuya Smart ou
Smart Life.

Há duas formas de obter a chave. A primeira não depende do portal `iot.tuya.com`:

**Pelo utilitário do repositório (recomendado)**

Usa o SDK oficial da Tuya com autorização por QR code — o mesmo mecanismo do
Home Assistant. Rode em qualquer máquina com internet:

```bash
python3 -m venv /tmp/tuya && /tmp/tuya/bin/pip install tuya-device-sharing-sdk cryptography qrcode
/tmp/tuya/bin/python scripts/tuya-local-key.py
```

1. No aplicativo, pegue o **User Code**: aba **Meu** → ícone de engrenagem (⚙️) → **Conta e Segurança** → **User Code**.
2. Informe o código quando o script pedir; ele mostra um QR code no terminal.
3. No aplicativo, toque em **+ (adicionar) → Escanear** e aponte para o QR.
4. O script lista cada dispositivo com **Device ID**, **chave local** e IP.

**Pelo painel de desenvolvedor (quando o `iot.tuya.com` estiver acessível)**

1. Crie uma conta gratuita em [iot.tuya.com](https://iot.tuya.com) (a mesma região do aplicativo; no Brasil costuma ser *Western America*).
2. Crie um **Cloud project** e ative o *IoT Core* (o plano de avaliação gratuito precisa ser renovado a cada seis meses).
3. Em **Devices → Link App Account**, vincule a conta do aplicativo onde a lâmpada foi pareada.
4. Em **API Explorer → Device Management → Query Device Details**, informe o Device ID e leia o campo `local_key`.

> O portal `iot.tuya.com` pode ficar inacessível de algumas redes; por isso o
> utilitário acima costuma ser o caminho mais confiável.

Se a chave mudar (por exemplo ao remover e adicionar a lâmpada de novo no
aplicativo), remova e cadastre a lâmpada novamente no Valk.

## Descoberta de endereço

O Valk guarda o último endereço funcional. Quando a lâmpada não responde, ele procura de novo pelo identificador do dispositivo nos anúncios da rede local. Redes com isolamento entre clientes, VLANs ou firewall precisam permitir que o container do Valk alcance a lâmpada na porta TCP 6668; a descoberta usa UDP 6666, 6667 e 7000.

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

## Segurança

- A chave local é cifrada no SQLite com a chave mestra AES-256-GCM da instalação.
- A API nunca devolve a chave para Android ou navegador.
- Todos os comandos passam pelo backend autenticado; o navegador nunca acessa a lâmpada diretamente.
- Somente administradores podem cadastrar, renomear, remover ou controlar a configuração de uma lâmpada. Qualquer usuário autenticado pode operar uma lâmpada já cadastrada.
- A descoberta só envia tráfego para as sub-redes locais às quais o host está conectado, e jamais para a internet.
