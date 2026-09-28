# Tradutor de Tela V1 (Android)

Protótipo Android que:

- captura a tela com MediaProjection após autorização do usuário;
- reconhece texto latino com ML Kit Text Recognition;
- identifica se o conteúdo está em inglês;
- traduz inglês → português com ML Kit Translation;
- mostra a tradução numa legenda flutuante sobre outros aplicativos;
- processa aproximadamente uma captura a cada 0,8 s para equilibrar velocidade, bateria e aquecimento.

## Requisitos

- Android Studio recente
- JDK 17+
- Android SDK 35 instalado
- Celular Android 6.0 (API 23) ou superior

## Como testar

1. Abra a pasta `TradutorTelaV1` no Android Studio.
2. Aguarde o Gradle sincronizar e baixar as dependências.
3. Conecte o celular com Depuração USB ou use um emulador.
4. Clique em **Run**.
5. No app, toque em **Autorizar sobreposição**.
6. Toque em **Iniciar tradução**.
7. Autorize a captura de tela no diálogo do Android.
8. Abra um jogo/site/app com texto em inglês.

Na primeira execução, o modelo inglês → português do ML Kit é baixado. Depois disso, a tradução pode funcionar localmente/offline.

## Limitações desta V1

- O texto traduzido é mostrado em um painel/legenda flutuante, não substitui cada frase exatamente na posição original.
- Telas protegidas contra captura podem aparecer vazias/pretas para o OCR.
- Se girar o aparelho durante a captura e a imagem ficar incorreta, pare e inicie a tradução novamente.
- OCR depende de tamanho, contraste e nitidez do texto original.

## Próximas versões sugeridas

- V2: caixas de tradução posicionadas sobre cada texto original.
- V3: botão/bolha flutuante com pausar, traduzir área e opacidade.
- V4: idiomas configuráveis e modo jogos.
- V5: histórico e favoritos.
