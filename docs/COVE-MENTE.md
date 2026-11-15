# La mente de Cove

Documento técnico del cerebro embebido en el mod. Explica qué hay, cómo funciona,
qué pesa, qué reutiliza y qué no hace.

## 1. Qué es

Cove es un agente que vive dentro del mod. Su lengua y su criterio no dependen de
un servicio externo: no hay Ollama, no hay LM Studio, no hay API key, no hay
tráfico de red. Todo el razonamiento es Java ejecutándose en el mismo proceso que
Minecraft.

La IA se compone de cuatro capas:

| Capa | Qué hace | Dónde |
| --- | --- | --- |
| Base de conocimiento | Qué significan las palabras y a qué acción del mod corresponde cada verbo | `covellite-agent/src/main/resources/cove/lexicon.txt` |
| Comprensión | Convierte texto libre en un acto de habla con entidades | `covellite-agent/src/main/java/covellite/agent/lang/Parser.java` |
| Generación | Convierte un significado en una frase española variable | `.../lang/Realizer.java` |
| Decisión | Elige callarse, hablar o actuar, y qué tool usar | `.../lang/OfflineBrain.java` |

La bisagra es `CoveProvider`: implementa la misma interfaz `AgentProvider` que los
proveedores HTTP, así que el runtime existente (loop de tools, memoria, árbitro de
cerebros, estado social, reflejos) funciona sin cambios.

## 2. Flujo de un mensaje

```
jugador escribe
   └─ Parser: acto de habla + entidades + negación + voseo + ¿responde la pregunta pendiente?
        └─ OfflineBrain: decide la respuesta (o el silencio) y, si es una orden, el tool y sus argumentos
             └─ AgentLoop: ejecuta el tool en el tick del servidor (authority del BrainArbiter)
                  └─ Realizer: confirma en una línea
                       └─ chat del juego
```

En paralelo, una vez por segundo, el mod entrega `SocialSignals` (jugadores, vida,
comida, noche, distancia al spawn, ocupación). `SocialPolicy` filtra si vale la pena
pensar y `OfflineBrain.decide` produce la decisión. Silencio es la respuesta normal.

## 3. Base de conocimiento

Archivo de texto plano, una entrada por línea:

```
CATEGORIA|canonico|sinonimos,separados,por,coma|accion|pista
```

Ejemplos reales:

```
ITEM|madera|tronco,troncos,lena,tabla,tablas,madero
PLACE|cueva|caverna,grieta,socavon,cuevas
MOB|zombi|zombie,zombis
VERB|cortar|talar,derribar,hachear|chop_tree|madera
VERB|minar|picar,excavar,cavar|mine|piedra
VERB|ir|andar,caminar,dirigirse,ir a|return_home|base
```

- Categorías: `ITEM`, `PLACE`, `MOB`, `ADJ`, `DIR`, `VERB`, `GREET`, `STATE`, `WORD`.
- Solo los verbos llevan acción; la acción es el nombre de una tool que ya existe.
- Los sinónimos se indexan al cargar, y la búsqueda tolera plurales y flexiones.
- El archivo se lee una sola vez, del classpath, y queda en memoria.

Para agregar vocabulario se edita ese archivo: no hace falta tocar Java. Para
agregar un verbo nuevo que use una tool existente, alcanza con una línea.

Tamaño actual: **4 KB**, ~110 entradas y ~300 formas indexadas.

## 4. Comprensión

`Parser` no busca palabras clave para disparar respuestas: analiza la oración.

1. Normaliza (minúsculas, sin acentos, sin signos) y tokeniza.
2. Detecta tipo de oración: pregunta (signos o interrogativo inicial), imperativo
   (formas de voseo: `andá`, `miná`, `seguime`, `volvé`), negación, saludo, risa.
3. Resuelve entidades con el léxico: items, lugares, criaturas, direcciones, números.
4. Distingue homónimos: en `miná hierro`, `mina` es verbo, no el lugar.
5. Detecta segunda persona (`vos`, `tenés`, `podés`, `che`) para saber si le hablan.
6. Si hay pregunta pendiente, decide si el mensaje la responde: sí/no directo,
   dato (dirección, número, lugar, item) o respuesta vaga.
7. Detecta **incongruencia**: una afirmación sobre una ubicación que contradice el
   mundo o usa un nombre que Cove no conoce (`la base está en Marte`) se marca como
   broma y no se toma como dato, pero sí cuenta como respuesta.

Actos posibles: `ORDEN`, `PREGUNTA_A_COVE`, `RESPUESTA_SI`, `RESPUESTA_NO`,
`RESPUESTA_DATO`, `BROMA`, `CORRECCION`, `SALUDO`, `CHARLA`, `AMBIGUA`.

## 5. Generación

`Realizer` tiene *planes* por acto de habla, con ranuras:

```
"dale, entonces para el {direccion}"
"ok, {lugar} entonces"
"jaja, seguro"
"tenés razón, me confundí"
```

Las ranuras se completan con lo que el jugador dijo y con la lectura del mundo. Los
planes rotan por acto y se descarta el que se parece demasiado a algo ya dicho en
las últimas diez salidas. Por eso una misma situación no produce dos veces la misma
frase. La personalidad ajusta el registro: más curiosidad inclina a observar, más
humor a contestar con ironía.

No hay una tabla de "si dice hola, responde X". Hay planes de discurso que se
seleccionan por acto, estado y novedad.

## 6. Decisión autónoma

`OfflineBrain.decide` sigue un orden explícito:

1. Si ya hay una pregunta sin responder: silencio (no se apilan preguntas).
2. Comida ≤ 6: iniciativa `comer`.
3. Vida < 8: aviso de herida.
4. Más de 220 bloques del spawn: iniciativa `volver a la base`.
5. Separado del jugador: iniciativa `seguir al jugador`.
6. Noche: pregunta si acampan.
7. Rasgo de iniciativa > 0.6: pregunta dónde están.
8. En cualquier otro caso: silencio.

`SocialPolicy` sigue siendo la que autoriza el ciclo (cooldowns, tope de iniciativas,
espera de respuesta), así que el decisor nunca puede spamear.

## 7. Acciones reutilizadas

Ninguna tool nueva. El mapeo usa las que ya existen:

| Verbo | Tool | Argumentos |
| --- | --- | --- |
| cortar / talar | `chop_tree` | — |
| minar / picar | `mine` | `what`: stone, iron, diamond, coal, gold, wood |
| ir / volver | `return_home` (o `explore_scan`) | — |
| seguir / venir / ayudar | `follow` | `player` |
| quedarse | `stay` | — |
| parar | `stop` | — |
| comer | `eat` | — |
| dormir | `sleep` | — |
| atacar | `attack` | `entity` |
| fabricar | `craft_item` | `item`, `count` |
| pescar | `fish` | — |
| explorar | `explore_scan` | — |
| mirar | `scan` | — |

Si la tool no está registrada en ese turno, Cove lo dice en vez de inventar una
acción. Los argumentos se validan antes de llamar; `follow` sin nombre de jugador
no se emite.

## 8. Integración

`CoveProvider.complete` responde a los tres prompts que el runtime ya enviaba:

| Prompt | Respuesta |
| --- | --- |
| Interpretación del mensaje | JSON con `intencion`, `responde_pendiente`, `dirigido_a_cove`, `tema`, `entendido`, `dato_nuevo` |
| Deliberación | JSON con `accion`, `mensaje`, `motivo`, `actividad`, `aprendizaje`, `animo`, `pregunta` |
| Turno normal | texto de chat, o uno o más tool calls |

Por eso se conservan sin cambios: `AgentRuntime`, `AgentLoop`, `AgentMemory`,
`AgentGoals`, `BrainArbiter`, `SocialState`, `Identity`, `SocialPolicy` y los
reflejos. El cerebro externo por MCP sigue disponible, pero es opcional.

## 9. Peso y rendimiento

| Elemento | Tamaño |
| --- | --- |
| Léxico (texto) | 4 KB |
| Clases del cerebro (7 clases) | ~70 KB de bytecode |
| JAR completo del mod | ~512 KB |

- Sin dependencias nuevas, sin descargas, sin modelos.
- Sin hilos nuevos: la comprensión y la generación corren en el hilo del agente.
- Coste por mensaje: recorrido de tokens y comparación contra ~300 formas. Microsegundos.
- Un ciclo de decisión no hace E/S: lee el estado en memoria y como mucho persiste
  `social/state.json`, que son unos pocos KB.

## 10. Límites honestos

- El vocabulario está cerrado al léxico. Una palabra nueva que no esté en el archivo
  no se entiende (se trata como nombre propio desconocido, lo que sirve para detectar
  bromas, pero no para actuar).
- No genera discurso libre: recombina planes con ranuras. Es coherente y variable,
  no creativo como un modelo grande.
- La comprensión es morfológica y estructural, no semántica profunda: ironías que no
  contradicen el mundo, ni preguntas encadenadas complejas.
- No recuerda hechos nuevos por sí sola salvo los que el runtime guarda (memoria,
  estado social, identidad).
- Nada de esto está verificado todavía dentro de una partida: está compilado y
  cubierto por 154 checks unitarios, no por juego real.

## 11. Cómo probar y extender

- Pruebas: `./gradlew :covellite-agent:agentTest` (154 checks).
- En juego: `/cove mind` muestra proveedor, autoridad, último motivo, pregunta
  pendiente, ánimo y rasgos.
- Extender el vocabulario: editar `cove/lexicon.txt`.
- Extender el habla: agregar planes en `Realizer`.
- Extender la comprensión: nuevas categorías o verbos en el léxico; el parser las
  toma sin cambios.
- Extender la decisión: nuevas reglas en `OfflineBrain.decide`, siempre detrás de
  `SocialPolicy`.
