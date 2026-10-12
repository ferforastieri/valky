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

A tela de iluminação já procura uma lâmpada em modo de pareamento enquanto está aberta: não existe botão de adicionar nem formulário de cadastro.

1. Abra **Dispositivos → Iluminação** no Android (apenas administradores).
2. Deixe a lâmpada **piscando rápido**: ligue e desligue três vezes até ela entrar em modo de pareamento.
3. Na primeira vez o aplicativo pede a senha do Wi-Fi, porque o Android não permite ler a senha salva do sistema para nenhum aplicativo. Depois disso ela fica cifrada no próprio aparelho e os próximos pareamentos são automáticos.
4. A lâmpada aparece na lista sozinha, já respondendo na rede local. Dê um nome e escolha o cômodo.

O fluxo inteiro leva menos de um minuto e não pede identificador, endereço nem chave: tudo isso é descoberto e gerado automaticamente.

## O que o pareamento faz

O aplicativo envia, no formato que a própria lâmpada espera (documentado pela comunidade em [tuya-convert](https://github.com/ct-Open-Source/tuya-convert), MIT), o nome da rede, a senha e três valores que o backend gerou (`region`, `token` e `secret`). Os valores ficam guardados no servidor por meia hora e são renovados a cada lâmpada configurada, então uma lâmpada que já recebeu o payload continua sendo aceita mesmo se a janela for reaberta. A lâmpada conecta-se à rede e passa a responder aos anúncios locais. O backend escuta esses anúncios durante a janela de pareamento e, para cada lâmpada nova, tenta as chaves que o dispositivo pode ter guardado, começando pelo valor gerado nesta sessão. A primeira que responder a uma leitura de estado vira a chave local da lâmpada — ela fica cifrada no SQLite e nunca é devolvida pela API.

Depois disso, o controle é 100% local: o Valkyris não usa nuvem para ligar, desligar, mudar cor, descobrir endereço nem ler o estado.

### Firmwares que exigem ativação

Algumas lâmpadas só aceitam controle local depois de uma ativação única no serviço do fabricante — é uma característica do firmware, não do Valkyris. Quando é esse o caso, o fluxo informa exatamente isso: a lâmpada aparece na rede, mas recusa a chave local, e a tela mostra a mensagem. O Valkyris **não** oferece caminho de ativação em nuvem: se a sua lâmpada estiver nesse grupo, o pareamento dirá o motivo em vez de prometer algo que não acontece.

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
