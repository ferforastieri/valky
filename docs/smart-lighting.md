# Iluminação inteligente local

O Valkyris controla lâmpadas Wi-Fi compatíveis com Tuya diretamente pela rede local. Depois do cadastro, é possível ligar, desligar e alterar brilho, temperatura ou cor.

## Compatibilidade inicial

A integração suporta os protocolos Tuya LAN 3.1 a 3.5 e foi preparada para lâmpadas com os pontos de controle modernos `switch_led`, `work_mode`, `bright_value_v2`, `temp_value_v2` e `colour_data_v2`. O primeiro modelo-alvo é a Avant NEO RGB 10 W, código 290028177.

O suporte real depende do firmware. Use **Testar conexão** no Android antes de bloquear o acesso da lâmpada à internet.

## Obter as credenciais locais

Cada dispositivo exige um `Device ID` e uma `Local Key` de 16 caracteres. A chave é criada durante o pareamento e muda sempre que a lâmpada é removida e adicionada novamente.

1. Pareie a lâmpada no Smart Life ou Tuya Smart usando uma rede Wi-Fi de 2,4 GHz.
2. Crie um projeto na Tuya Developer Platform e vincule a mesma conta.
3. Execute o assistente do [TinyTuya](https://github.com/jasonacox/tinytuya) para gerar `devices.json`.
4. No Android, abra **Dispositivos → Iluminação → Adicionar** e selecione **Importar devices.json do TinyTuya**.
5. Confira o nome e o cômodo e salve. O endereço IP e a versão do protocolo presentes no arquivo serão usados apenas como ponto de partida.

Também é possível informar os valores manualmente. Nunca envie a `Local Key` em mensagens, issues ou capturas de tela.

## Descoberta de endereço

O Valkyris guarda o último endereço funcional, tenta a descoberta Tuya por UDP e procura o dispositivo novamente quando necessário usando o `Device ID` e a chave já cadastrados.

Redes com isolamento entre clientes, VLANs ou firewall precisam permitir que o container do Valkyris alcance a lâmpada na porta TCP 6668. A descoberta usa UDP 6666, 6667 e 7000 quando esses broadcasts chegam ao container.

## Segurança

- A `Local Key` é cifrada no SQLite com a chave mestra AES-256-GCM da instalação.
- A API nunca devolve a chave para Android ou navegador.
- Endereços informados no cadastro precisam pertencer a uma rede privada.
- Todos os comandos passam pelo backend autenticado; o navegador nunca acessa a lâmpada diretamente.
- Somente administradores podem cadastrar, editar, testar ou remover. Qualquer usuário autenticado pode operar uma lâmpada já cadastrada.
- O cliente Tuya LAN em Go está fixado na versão `v1.0.1` e no checksum do `go.sum`; o código-fonte e a licença MIT dessa versão permanecem disponíveis no proxy oficial do Go e no pkg.go.dev para builds reproduzíveis e auditoria.

Se a lâmpada for resetada ou pareada novamente, edite-a pelo Android e informe a nova `Local Key`.
