# Apresentação de alertas por câmera

No Android, abra **Câmeras → Editar câmera → Notificações e alarme**. É possível
editar o título e a mensagem de notificações e de alarmes, escolher alarme padrão,
toque padrão ou sem som, ativar vibração e permitir abertura em tela cheia.
Uma prévia mostra os textos. Campos vazios usam o texto traduzido do aplicativo.
As regras continuam decidindo quais eventos geram notificação ou alarme e quem
os recebe. A configuração da câmera vale para todos os destinatários.

O objeto `alerts` está disponível em GET/POST/PUT de câmeras. Em atualizações,
omitir o objeto preserva a configuração; enviar `{}` restaura os padrões. Valores
`false` são preservados. Títulos têm até 80 caracteres, mensagens até 240 e o total
dos textos até 1600 bytes UTF-8, para caber no envelope cifrado do FCM. Instalações
existentes recebem os padrões pela migração automática, sem mudar as regras.

O som usa um serviço de reprodução em primeiro plano, com atributos e foco de
áudio de alarme. Não depende da Activity de tela cheia. Se o toque do aparelho
não puder ser aberto, há um som de reserva incluído no APK. Silenciar pela tela
ou notificação interrompe a reprodução; confirmar um evento também interrompe.
Sem interação, a reprodução termina após dois minutos, preservando a notificação.
A confirmação só remove a notificação após sucesso no servidor; falhas na tela de
alarme permitem tentar novamente. Volume de alarme, permissões do sistema e
restrições de execução do fabricante continuam sendo respeitados. Se o Android
impedir o serviço em segundo plano, a notificação mantém seu som de sistema.

A tela usa o tema escolhido no app, margens das barras do sistema e conteúdo com
rolagem, inclusive com fontes ampliadas. O vermelho destaca o alarme dentro da
paleta das outras telas.

Validação em aparelho: instalar o APK e atualizar o backend; disparar uma regra
com tela bloqueada e desbloqueada, com tela cheia ativada e desativada; conferir
som, silêncio e confirmação no Moto Edge e Xiaomi. Verificar também o volume de
**alarme** no Xiaomi, o canal de notificações e as permissões de execução do app.
Testes automatizados não reproduzem as restrições específicas do HyperOS/MIUI.

Referências: [foco de áudio](https://developer.android.com/media/optimize/audio-focus)
e [reprodução em primeiro plano](https://developer.android.com/develop/background-work/services/fgs/service-types#media).
