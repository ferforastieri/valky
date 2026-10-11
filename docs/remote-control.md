# Controle remoto (Samsung Smart TV)

A seção **Dispositivos** do aplicativo reúne câmeras, iluminação e controles
remotos. As câmeras continuam sendo a página inicial; o controle remoto é uma
nova aba ao lado da iluminação.

O backend é a ponte entre o celular e a TV: o app nunca fala diretamente com a
TV, então o controle funciona pela mesma sessão autenticada, pela LAN ou por
VPN. O token que a TV emite fica cifrado no SQLite (AES-256-GCM) e nunca é
devolvido pela API.

## Como a TV é controlada

As TVs Samsung com Tizen (2016+) expõem um canal WebSocket de controle remoto:

```text
wss://TV:8002/api/v2/channels/samsung.remote.control?name=<base64 do app>&token=<token>
```

- `name` é o nome do controlador, codificado em base64; a TV o mostra no aviso
  de autorização.
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

## Pareamento

Na primeira conexão a TV mostra um aviso do tipo *"Permitir que Valkyris
acesse este dispositivo?"*. Ao aceitar, a TV devolve um token que o backend
guarda cifrado. Depois disso os comandos não pedem mais autorização.

1. No app, abra **Dispositivos → Controle remoto → +**.
2. Informe o nome e o endereço IP da TV (ex.: `192.168.0.10`). A porta padrão
   é `8002`.
3. Toque no controle criado e em **Parear com a TV**.
4. Aceite o aviso na tela da TV. O estado passa de *Não pareado* para
   *Pareado*.

Se a TV trocar o token (por exemplo, ao remover o controlador nas configurações
dela), basta parear de novo.

## Comandos disponíveis

O backend aceita apenas uma lista fixa de teclas Samsung; nada além disso é
repassado à TV. A API rejeita qualquer comando fora dessa lista.

`KEY_POWER`, `KEY_UP`, `KEY_DOWN`, `KEY_LEFT`, `KEY_RIGHT`, `KEY_ENTER`,
`KEY_RETURN`, `KEY_HOME`, `KEY_MENU`, `KEY_EXIT`, `KEY_VOLUP`, `KEY_VOLDOWN`,
`KEY_MUTE`, `KEY_CHUP`, `KEY_CHDOWN`, `KEY_CH_LIST`, `KEY_PRECH`, `KEY_PLAY`,
`KEY_PAUSE`, `KEY_STOP`, `KEY_REWIND`, `KEY_FF`, `KEY_REC`, `KEY_INFO`,
`KEY_GUIDE`, `KEY_TOOLS`, `KEY_SOURCE`, `KEY_TV`, `KEY_HDMI`, `KEY_0` a `KEY_9`,
`KEY_RED`, `KEY_GREEN`, `KEY_YELLOW`, `KEY_BLUE`.

## Limites

- A TV precisa estar ligada e acessível a partir do servidor. Ligar uma TV
  totalmente desligada exige Wake-on-LAN e não é suportado; o botão de energia
  desliga.
- Não há cadastro público nem nuvem: o controle é apenas para a sua instalação.
- O backend não faz descoberta automática de TVs; informe o IP (reserve-o no
  roteador para ele não mudar).

## API

| Método | Rota | Acesso | Finalidade |
| --- | --- | --- | --- |
| `GET` | `/api/v1/remotes` | Autenticado | Lista os controles e o estado de pareamento. |
| `POST` | `/api/v1/remotes` | Administrador | Cadastra um controle (`name`, `host`, `port`). |
| `PUT` | `/api/v1/remotes/{id}` | Administrador | Edita nome, endereço, porta e estado. |
| `DELETE` | `/api/v1/remotes/{id}` | Administrador | Remove o controle. |
| `POST` | `/api/v1/remotes/{id}/pair` | Administrador | Pareia com a TV (aceite o aviso nela). |
| `POST` | `/api/v1/remotes/{id}/keys` | Autenticado | Envia uma tecla (`{ "key": "KEY_HOME" }`). |
