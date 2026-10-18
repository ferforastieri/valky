# Capturas reais do app

Estas imagens são renderizadas pelos próprios componentes Jetpack Compose usados pelo Valky. Elas não são mockups desenhados na landing page.

Para regenerar todas as capturas depois de uma mudança visual:

```sh
cd mobile
./gradlew :app:recordRoborazziDebug -Pscreenshots --tests '*LandingScreenshotsTest*'
```

As imagens saem em `mobile/app/build/screenshots/` com o prefixo `landing-` e são
copiadas para cá com o nome que a landing usa:

| Gravada pelo teste | Arquivo da landing |
| --- | --- |
| `landing-overview-{light,dark}` | `overview-{light,dark}.png` |
| `landing-cameras-{light,dark}` | `cameras-{light,dark}.png` |
| `landing-events-{light,dark}` | `events-{light,dark}.png` |
| `landing-rules-{light,dark}` | `rules-{light,dark}.png` |
| `landing-settings-{light,dark}` | `settings-{light,dark}.png` |
| `landing-lista-{light,dark}` | `luz-lista-{light,dark}.png` |
| `landing-luz-{light,dark}` | `luz-{light,dark}.png` |

O teste grava as telas em PT-BR, nas variantes clara e escura, com viewport de
411 × 891 dp (1233 × 2673 px). Apenas as imagens usadas pela landing fazem parte
do repositório; capturas comparativas da própria página não são versionadas.
