# Capturas reais do app

Estas imagens são renderizadas pelos próprios componentes Jetpack Compose usados pelo Valkyris. Elas não são mockups desenhados na landing page.

Para regenerar todas as capturas após uma mudança visual:

```sh
cd mobile
./gradlew :app:recordRoborazziDebug --tests '*AppShowcaseScreenshotTest*'
```

O teste grava as telas e os sheets de idioma, permissões e retenção em PT-BR, nas variantes clara e escura, com viewport de 390 × 844 dp. Apenas essas imagens usadas pela landing fazem parte do repositório; capturas comparativas da própria página não são versionadas.
