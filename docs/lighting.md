# Iluminação local

O Valkyris preserva um modelo comum de iluminação para ligar, desligar e, quando o dispositivo oferecer essas capacidades, alterar brilho, temperatura do branco e cor RGB.

## Arquitetura

O domínio de iluminação não depende de conta ou protocolo de um fabricante. O backend expõe uma fronteira de driver local com três operações:

- descoberta do dispositivo na rede;
- leitura do estado atual;
- aplicação de uma alteração de estado.

Um adaptador concreto implementa essa fronteira e traduz suas capacidades para o modelo comum. Sem um adaptador instalado, o Valkyris não tenta descobrir dispositivos nem se conecta a uma nuvem.

## Interfaces preservadas

- O Android mantém a área **Dispositivos → Iluminação** e os controles comuns.
- O painel web continua preparado para consultar e controlar dispositivos publicados pelo backend.
- A API mantém leitura, controle e remoção de registros, mas o cadastro pertence ao futuro fluxo do adaptador escolhido.
- Registros criados por versões anteriores não são apagados automaticamente e podem ser removidos por um administrador.

## Requisitos para novos adaptadores

Novos adaptadores devem operar localmente, declarar capacidades em vez de assumir recursos, manter credenciais somente no backend e não exigir que o aplicativo conheça detalhes do protocolo. O fluxo de cadastro deve ser orientado pelo próprio padrão e não por campos genéricos de chaves, IPs ou versões.
