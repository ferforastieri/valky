# Iluminação local

O Valkyris controla lâmpadas Wi-Fi compatíveis com Tuya (protocolos locais 3.1 a 3.5) diretamente na rede local. Depois do cadastro, é possível ligar, desligar e, quando o dispositivo oferecer essas capacidades, alterar brilho, temperatura do branco e cor RGB.

Nenhuma conta, aplicativo ou serviço de fabricante participa da descoberta ou do controle: o backend fala com a lâmpada na porta TCP 6668 da própria casa. O único momento em que a nuvem do fabricante pode entrar é a obtenção da chave local, explicada abaixo, e ela não é usada depois.

## Arquitetura

O domínio de iluminação não depende de conta ou protocolo de um fabricante. O backend expõe uma fronteira de driver local com quatro operações:

- descoberta do dispositivo na rede;
- validação das credenciais e leitura das capacidades (pareamento);
- leitura do estado atual;
- aplicação de uma alteração de estado.

`internal/light/tuya` implementa essa fronteira para os protocolos Tuya LAN 3.1 a 3.5. O protocolo foi documentado pela comunidade em [tinytuya](https://github.com/jasonacox/tinytuya) e [tuyapi](https://github.com/codetheweb/tuyapi) (ambos MIT); a implementação do Valkyris é própria, cobre apenas descoberta e leitura/escrita de pontos de controle, e é verificada contra quadros de referência gravados, sem depender de bibliotecas de terceiros.

## Como cadastrar

1. Abra **Dispositivos → Iluminação → Adicionar** no Android (apenas administradores).
2. Toque em **Procurar na rede**. O backend envia o mesmo anúncio que o aplicativo do fabricante usa e lista o que responder na rede local.
3. Selecione a lâmpada, informe um nome, o cômodo e a **chave local** de 16 caracteres.
4. Salve. O Valkyris fala com a lâmpada na hora: descobre a versão de protocolo que funciona, quais pontos de controle ela expõe (brilho, branco, cor) e o estado atual. O resultado aparece na própria tela.

Também é possível cadastrar sem a descoberta, informando o identificador do dispositivo (20 caracteres na maioria dos firmwares) e o endereço.

### Chave local

Cada lâmpada tem uma chave de 16 caracteres, criada quando ela é pareada. Ela é o único segredo necessário para controle local e **nunca** é devolvida pela API: fica cifrada com AES-256-GCM no SQLite, ao lado das credenciais das câmeras.

Formas de obtê-la, da mais local para a menos:

- **Reaproveitar o que já existe**: se a lâmpada já estava pareada em um aplicativo compatível, a chave está no projeto de nuvem do fabricante. O assistente do [TinyTuya](https://github.com/jasonacox/tinytuya) gera um `devices.json` a partir dele, de onde a chave pode ser copiada.
- **Observar o pareamento**: a chave é entregue à lâmpada no pareamento. Quem observa esse tráfego na rede Wi-Fi (por exemplo com o [tuya-cloudcutter](https://github.com/tuya-cloudcutter/tuya-cloudcutter)) obtém a chave sem conta nenhuma.
- **Parear de novo**: refazer o pareamento descarta a chave antiga e cria uma nova, que precisa ser capturada no mesmo momento.

### Pareamento do zero

Uma lâmpada que nunca foi vinculada a nenhuma conta pode ser adotada sem passar pela nuvem: ela ainda aceita ser configurada pelos comandos locais de pareamento. Esse caminho precisa de um rádio Wi-Fi na mesma rede da lâmpada, porque a configuração é entregue no ar (modo EZ/smartconfig) ou pela rede que a própria lâmpada cria (modo AP). O servidor do Valkyris normalmente está no cabo, então o pareamento do zero pertence ao aplicativo Android, que já está no Wi-Fi:

1. a lâmpada é resetada (ligar e desligar três vezes) e passa a piscar rápido;
2. o aplicativo envia a configuração com uma chave local gerada por ele mesmo, sem conta nenhuma;
3. quando a lâmpada aparece online, o aplicativo a cadastra no Valkyris com essa chave.

O adaptador do backend já aceita esse cadastro: basta o identificador do dispositivo e a chave. Enquanto o fluxo de pareamento pelo aplicativo não estiver implementado e validado com uma lâmpada em modo de pareamento, use uma das formas acima para obter a chave uma única vez.

Se a lâmpada for resetada ou pareada novamente, edite-a no Android e informe a nova chave.

## Verificação pelo servidor

O passo de pareamento também pode ser conferido sem o aplicativo, direto no servidor:

```bash
cd backend
go run ./cmd/lightscan                      # lista o que responde na rede local
go run ./cmd/lightscan -probe               # também testa a porta de controle de cada endereço
go run ./cmd/lightscan -id <dispositivo> -key <chave> -address 192.168.1.60
```

O utilitário usa exatamente o mesmo caminho do backend: sem conta, sem nuvem e sem enviar nada para fora da rede local.

## Descoberta de endereço

O Valkyris guarda o último endereço funcional. Quando a lâmpada não responde, ele procura de novo pelo identificador do dispositivo nos anúncios da rede local. Redes com isolamento entre clientes, VLANs ou firewall precisam permitir que o container do Valkyris alcance a lâmpada na porta TCP 6668; a descoberta usa UDP 6666, 6667 e 7000.

O endereço de uma lâmpada muda com o tempo por causa do DHCP, e o Valkyris não depende dele: o identificador é o que identifica o dispositivo.

## Capacidades e pontos de controle

A lâmpada informa quais pontos de controle expõe; o adaptador detecta o layout e o guarda por dispositivo:

| Layout | Liga/desliga | Modo | Brilho | Branco | Cor |
| --- | --- | --- | --- | --- | --- |
| moderno | 20 | 21 | 22 (10–1000) | 23 (0–1000) | 24 (HSV em hexadecimal) |
| legado | 1 | 2 | 3 (25–255) | 4 (0–255) | 5 (RGB em hexadecimal) |
| branco | 1 | — | 2 (25–255) | 3 (0–255) | — |

Brilho é guardado em porcentagem e temperatura em Kelvin: o valor bruto do firmware nunca chega ao aplicativo. A temperatura é mapeada linearmente entre 2700 K e 6500 K.

## Segurança

- A chave local é cifrada no SQLite com a chave mestra AES-256-GCM da instalação.
- A API nunca devolve a chave para Android ou navegador.
- Todos os comandos passam pelo backend autenticado; o navegador nunca acessa a lâmpada diretamente.
- Somente administradores podem cadastrar, editar, testar ou remover. Qualquer usuário autenticado pode operar uma lâmpada já cadastrada.
- A descoberta só envia tráfego para as sub-redes locais às quais o host está conectado, e jamais para a internet.
- Cadastrar exige uma chave de 16 caracteres; o pareamento é validado contra o dispositivo antes de substituir uma configuração que já funcionava.
