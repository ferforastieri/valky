# Movimento persistente em uma região

Em Regras (ou nas regras da câmera), selecione **Movimento**, habilite **Movimento persistente em uma região** e arraste sobre a imagem para delimitar o berço ou outra região. Selecione o tempo mínimo, a porcentagem mínima de pixels alterados e o intervalo entre alertas. A região tem coordenadas relativas à imagem e precisa medir pelo menos 5% da largura e altura.

**Limitar por horário** permite selecionar dias, início, fim e fuso IANA. Segunda 22:00–06:00 inclui terça até 05:59; 06:00 está fora da janela. Sem horário, a regra funciona o dia todo. Esses valores são parâmetros de software, não limites clínicos de segurança.

O servidor analisa quadros novos do RTSP, inclusive nas câmeras com eventos ONVIF. Só alterações dentro da região contam. Eventos genéricos ONVIF não satisfazem regras com região. A comparação usa luminância e amostragem espacial, com intervalo de cerca de 2 segundos mais o tempo de captura. Não é análise contínua de todos os frames: movimentos entre amostras podem passar despercebidos. Uma amostra sem movimento, falha na captura ou intervalo maior que 12 segundos reinicia a contagem. Mudanças em 80% ou mais da imagem inteira também reiniciam a contagem, para reduzir alertas de troca do infravermelho/exposição. Isso pode omitir movimentos reais que ocupem quase toda a imagem.

O sistema detecta movimento na região, não identidade do bebê, postura, respiração, sufocamento, convulsões ou perigo médico. Adultos, cobertas e outros objetos na região podem gerar alertas. Não substitui supervisão e práticas de sono seguro. Valide com a visão noturna real e movimentos conhecidos antes de usar os alertas.

Mantenha a câmera fixa e desative rastreamento automático/PTZ enquanto usa regiões. Após mudar o enquadramento, marque novamente a região. Não há rastreamento da região após movimento da câmera. Use regras diferentes para regiões e durações distintas; delimitar a borda só detecta movimento ali, não comprova saída do bebê.

Requer backend e APK atualizados juntos. Regras antigas permanecem com comportamento padrão. O armazenamento migra sem remover regras existentes. Clientes antigos não expõem estes controles e podem removê-los ao editar uma regra, portanto use o APK novo para editar as regras avançadas. Nenhuma regra é criada automaticamente e não são alteradas configurações nativas da Tapo.
