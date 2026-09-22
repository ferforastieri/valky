export type Locale = 'pt-BR' | 'en';

export const copy = {
  'pt-BR': {
    metaTitle: 'Valkyris | Monitoramento e controle residencial self-hosted',
    metaDescription: 'Monitore câmeras ONVIF e controle iluminação inteligente pelo Android e pela web, com processamento local, clipes privados e alertas.',
    nav: ['Visão geral', 'Aplicativo', 'Instalação', 'Documentação'],
    heroEyebrow: 'Monitoramento e controle residencial',
    heroTitle: 'Sua casa avisa. Seus dados ficam.',
    heroBody: 'Transforme seu homelab em uma central residencial self-hosted: acompanhe câmeras ONVIF, reconheça sons e movimentos, controle a iluminação e receba alertas no Android.',
    install: 'Instalar com Docker',
    github: 'Ver no GitHub',
    live: 'AO VIVO',
    camera: 'Entrada principal',
    status: 'Proteção ativa',
    alert: 'Choro detectado',
    alertMeta: 'Agora · 91% de confiança',
    principlesTitle: 'Sua privacidade importa.',
    principlesBody: 'Vídeo, áudio, regras e clipes permanecem na sua instalação. O celular acessa sua casa por LAN ou VPN, sem enviar sua rotina para um servidor central.',
    detectBody: 'Combine tipos de detecção, confirmações, horários e intervalo entre alertas para reduzir falsos positivos.',
    appTitle: 'Uma interface calma até algo pedir atenção.',
    appBody: 'Câmeras ao vivo, movimentos PTZ, iluminação, timeline, clipes e regras em um app Kotlin nativo com tema claro e escuro.',
    setupTitle: 'Comece em três passos',
    steps: [
      ['Prepare a câmera', 'Conecte-a ao Wi-Fi e crie a Camera Account RTSP/ONVIF. Na TC40, o app Tapo só é necessário nesta etapa.'],
      ['Suba o Valkyris', 'Execute o instalador em um Linux com Docker que alcance a mesma rede da câmera.'],
      ['Entre pelo Android', 'Informe seu endereço HTTPS e crie a conta com usuário e senha no primeiro acesso. Convites para outros celulares nascem dentro do app.']
    ],
    compatibilityTitle: 'Compatibilidade honesta',
    compatibilityBody: 'Câmeras ONVIF Profile S com RTSP funcionam por capacidades: PTZ, áudio, snapshots e eventos só aparecem quando o dispositivo os anuncia. A TC40 oferece vídeo, áudio, eventos e PTZ; áudio bidirecional, sirene e holofote proprietários ficam fora.',
    faqTitle: 'Perguntas frequentes',
    faq: [
      ['Quais câmeras são compatíveis?', 'Câmeras ONVIF Profile S com RTSP funcionam por capacidades. PTZ, áudio, snapshots e eventos aparecem apenas quando o dispositivo os anuncia. A TC40 oferece vídeo, áudio, eventos e PTZ; áudio bidirecional, sirene e holofote proprietários ficam fora.'],
      ['Preciso abrir portas no roteador?', 'Não. Use LAN em casa e uma VPN como Tailscale ou WireGuard fora dela.'],
      ['O Valkyris grava o tempo todo?', 'A mídia mantém apenas um buffer curto. Um clipe permanente só nasce quando uma regra dispara.'],
      ['A detecção acerta sempre?', 'Nenhum classificador acerta sempre. Por isso cada regra combina tipos de detecção, confirmações e cooldown.'],
      ['Posso usar outra câmera?', 'Sim, desde que ofereça ONVIF Profile S e RTSP. Recursos variam conforme as capacidades anunciadas.'],
      ['Posso controlar lâmpadas?', 'Sim. Dispositivos Tuya LAN compatíveis podem ser cadastrados no Android e controlados pelo aplicativo e pelo painel web.']
    ],
    footer: 'Software independente, aberto e feito para redes privadas.',
    disclaimer: 'Valkyris não é afiliado à TP-Link ou Tapo.'
  },
  en: {
    metaTitle: 'Valkyris | Self-hosted home monitoring and control',
    metaDescription: 'Monitor ONVIF cameras and control smart lighting from Android and the web with local processing, private clips and alerts.',
    nav: ['Overview', 'Application', 'Install', 'Documentation'],
    heroEyebrow: 'Private home monitoring and control',
    heroTitle: 'Your home speaks. Your data stays.',
    heroBody: 'Turn your homelab into a self-hosted home hub: monitor ONVIF cameras, recognize sounds and movement, control lighting, and receive Android alerts.',
    install: 'Install with Docker', github: 'View on GitHub', live: 'LIVE', camera: 'Front entrance', status: 'Protection active', alert: 'Baby cry detected', alertMeta: 'Now · 91% confidence',
    principlesTitle: 'Your privacy matters.', principlesBody: 'Video, audio, rules and clips remain on your installation. Your phone reaches home over LAN or VPN without sending your routine to a central server.',
    detectBody: 'Combine detector types, confirmations, schedules and cooldowns to reduce false positives.',
    appTitle: 'A calm interface until something needs attention.', appBody: 'Live cameras, PTZ motion, lighting, timeline, clips and rules in a native Kotlin app with light and dark themes.',
    setupTitle: 'Start in three steps', steps: [['Prepare the camera','Connect it to Wi-Fi and create the RTSP/ONVIF camera account. For TC40, Tapo is only needed at this stage.'],['Run Valkyris','Execute the installer on a Docker Linux host that can reach the camera network.'],['Sign in on Android','Enter your HTTPS address and create the personal username and password on first access. Invitations for other phones are generated inside the app.']],
    compatibilityTitle: 'Honest compatibility', compatibilityBody: 'ONVIF Profile S cameras with RTSP work by capability: PTZ, audio, snapshots and events only appear when advertised. TC40 offers video, audio, events and PTZ; proprietary two-way audio, siren and spotlight are out of scope.',
    faqTitle: 'Frequently asked questions', faq: [['Which cameras are compatible?','ONVIF Profile S cameras with RTSP work by capability. PTZ, audio, snapshots and events appear only when advertised. TC40 offers video, audio, events and PTZ; proprietary two-way audio, siren and spotlight are out of scope.'],['Must I open router ports?','No. Use LAN at home and a VPN such as Tailscale or WireGuard away from it.'],['Does Valkyris record continuously?','Media keeps only a short buffer. A permanent clip is created only when a rule fires.'],['Is detection always correct?','No classifier is perfect. Every rule can combine detector types, confirmations and cooldown.'],['Can I use another camera?','Yes, if it provides ONVIF Profile S and RTSP. Features depend on advertised capabilities.'],['Can I control light bulbs?','Yes. Compatible Tuya LAN devices can be registered on Android and controlled from the app and web dashboard.']],
    footer: 'Independent open software made for private networks.', disclaimer: 'Valkyris is not affiliated with TP-Link or Tapo.'
  }
} as const;
