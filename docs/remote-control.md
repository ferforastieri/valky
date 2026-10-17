# Controle remoto de TVs

A seção **Dispositivos** do aplicativo reúne câmeras, iluminação e controles
remotos. As câmeras continuam sendo a página inicial; o controle remoto é uma
aba ao lado da iluminação, com seletor apenas por ícones e troca por deslize
lateral.

O backend é a ponte entre o celular e a TV: o app nunca fala diretamente com a
TV, então o controle funciona pela mesma sessão autenticada, pela LAN ou por
VPN. O token que a TV emite fica cifrado no SQLite (AES-256-GCM) e nunca é
devolvido pela API.

O domínio é **agnóstico de marca**. O primeiro driver implementa o canal
WebSocket usado por TVs com Tizen; outros protocolos podem ser adicionados como
novos drivers sem mudar a API nem o app. O controle **apenas envia teclas**:
ele não altera nenhuma configuração da TV.

## Como a TV é controlada

As TVs com Tizen (2016+) expõem um canal WebSocket de controle remoto:

```text
wss://TV:8002/api/v2/channels/samsung.remote.control?name=<base64 do app>&token=<token>
```

- `name` é o nome do controlador, codificado em base64; a TV o mostra no aviso
  de autorização. O caminho do canal é um identificador fixo do protocolo e é
  usado exatamente como a TV espera.
- A porta `8002` usa TLS. Como a TV apresenta um certificado autoassinado, o
  backend valida a alcançabilidade, mas não a cadeia de certificados.
- Modelos mais antigos expõem o canal sem TLS na porta `8001`. O backend tenta
  a porta configurada e recorre a `8002`/`8001` quando necessário.
- Após a conexão, o backend envia um envelope JSON por comando:

```json
{
  "method": "ms.remote.control",
  "params": {
    "Cmd": "Click",
    "DataOfCmd": "KEY_HOME",
    "Option": "false",
    "TypeOfRemote": "SendRemoteKey"
  }
}
```

## Descoberta e pareamento

Ao abrir a aba de controle remoto, o app **já procura TVs sozinho** na rede do
celular (SSDP/UPnP, com varredura da sub-rede local nas portas de controle das
principais plataformas de TV como alternativa quando o roteador bloqueia
multicast). As TVs encontradas aparecem em **Encontradas na rede**; tocar em uma
delas cadastra o controle e inicia o pareamento.

Na primeira conexão a TV mostra um aviso do tipo *"Permitir que Valk acesse
este dispositivo?"*. Ao aceitar, a TV devolve um token que o backend guarda
cifrado. Depois disso os comandos não pedem mais autorização.

Também é possível cadastrar manualmente (nome e IP da TV, porta padrão `8002`).

Se a TV trocar o token (por exemplo, ao remover o controlador nas configurações
dela), basta parear de novo.

## Comandos disponíveis

O backend aceita apenas uma lista fixa de teclas; nada além disso é repassado à
TV. A API rejeita qualquer comando fora dessa lista.

`KEY_POWER`, `KEY_UP`, `KEY_DOWN`, `KEY_LEFT`, `KEY_RIGHT`, `KEY_ENTER`,
`KEY_RETURN`, `KEY_HOME`, `KEY_MENU`, `KEY_EXIT`, `KEY_VOLUP`, `KEY_VOLDOWN`,
`KEY_MUTE`, `KEY_CHUP`, `KEY_CHDOWN`, `KEY_CH_LIST`, `KEY_PRECH`, `KEY_PLAY`,
`KEY_PAUSE`, `KEY_STOP`, `KEY_REWIND`, `KEY_FF`, `KEY_REC`, `KEY_INFO`,
`KEY_GUIDE`, `KEY_TOOLS`, `KEY_SOURCE`, `KEY_TV`, `KEY_HDMI`, `KEY_0` a `KEY_9`,
`KEY_RED`, `KEY_GREEN`, `KEY_YELLOW`, `KEY_BLUE`.

## Aplicativos instalados

A Samsung removeu, a partir do Tizen 5 (2020+), a API que listava os aplicativos
instalados na TV. Por isso o controle do Valk **não exibe a barra de apps**:
não existe chamada que devolva a lista completa nesta geração de firmware.

## Ligar e desligar a TV

O botão de energia faz as duas coisas com um toque: se a TV estiver **ligada**
(e acessível), envia `KEY_POWER` para desligá-la; se estiver em **standby**, envia
um magic packet (Wake-on-LAN, UDP porta 9) para ligá-la. Não é preciso informar o
MAC — ele é capturado automaticamente ao parear.

**Na TV (uma vez):**
1. Configurações → Geral → Rede → Configurações especialistas → **Power On with
   Mobile** → **Ligar**.
2. Deixe a TV em **standby** (desligada pelo controle), não fora da tomada.

**No servidor:** como o backend roda em Docker, o firewall do CT precisa
permitir `UDP 9` para o broadcast da LAN (ex.: `192.0.2.0/24`).

**Limites:** Wi‑Fi (WoWLAN) é bem menos confiável que cabo Ethernet — pode
precisar de 2–3 tentativas e alguns modelos/roteadores não acordam por Wi‑Fi.

## Latência

As teclas reutilizam uma **conexão WebSocket mantida aberta** por TV, em vez de
abrir (e refazer o handshake) a cada toque. O app também não bloqueia um toque
esperando o anterior: o backend serializa os comandos por aparelho, então os
toques saem em ordem e quase instantâneos. Se a TV fechar a conexão (standby,
reinício), o backend reconecta sozinho na próxima tecla.

## Limites

- A TV precisa estar ligada e acessível a partir do servidor para receber teclas.
  Em standby, use o botão de energia (Wake-on-LAN).
- A descoberta depende da rede: isolar clientes (Wi-Fi de convidados, VLANs) ou
  bloquear multicast impede achar a TV automaticamente — nesse caso use o
  cadastro manual.
- O controle não altera configurações da TV; apenas envia teclas.
- Reserve o IP da TV no roteador para ele não mudar.

## API

| Método | Rota | Acesso | Finalidade |
| --- | --- | --- | --- |
| `GET` | `/api/v1/remotes` | Autenticado | Lista os controles e o estado de pareamento. |
| `POST` | `/api/v1/remotes` | Administrador | Cadastra um controle (`name`, `host`, `port`). |
| `PUT` | `/api/v1/remotes/{id}` | Administrador | Edita nome, endereço, porta e estado. |
| `DELETE` | `/api/v1/remotes/{id}` | Administrador | Remove o controle. |
| `POST` | `/api/v1/remotes/{id}/pair` | Administrador | Pareia com a TV (aceite o aviso nela). |
| `POST` | `/api/v1/remotes/{id}/keys` | Autenticado | Envia uma tecla (`{ "key": "KEY_HOME" }`). |
| `POST` | `/api/v1/remotes/{id}/wake` | Autenticado | Envia o Wake-on-LAN (liga a TV). |
| `POST` | `/api/v1/remotes/{id}/power` | Autenticado | Liga ou desliga a TV conforme o estado atual. |
