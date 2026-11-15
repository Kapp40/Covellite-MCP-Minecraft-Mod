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

Hay tres fuentes, en este orden:

1. `/cove/lexicon.txt` — la base curada que viaja en el jar.
2. `/cove/lexicon-extra.txt` — adicionales empaquetados (opcional).
3. `<partida>/covemcp/lexicon-extra.txt` — **el archivo que el jugador puede editar**
   (el mod lo apunta con la propiedad `covemcp.lexiconExtra`).

### Entrenarlo sin recompilar

Para enseñarle vocabulario nuevo alcanza con agregar líneas al archivo editable y
reabrir el juego. Ejemplos:

```
ITEM|mate|mates
VERB|matear|mateando,tomar mate|eat|comida
MOB|carpincho|carpinchos
PHRASE|greet|que hacés vos,como te va la vida
PHRASE|elogio|sos un fenómeno,qué grande
```

No hay pesos ni reentrenamiento: es recall léxico. La ventaja es que es inmediato,
predecible y pesa kilobytes.

### Conjugación completa

Cada verbo se indexa en **todos los tiempos** (presente, pretérito, imperfecto,
futuro, condicional, subjuntivo, imperativo voseo, gerundio, participio) y también
en las conjugaciones de los infinitivos escondidos en sus sinónimos. Por eso
`talaríamos`, `rompieron`, `picá` o `seguime` resuelven a la acción correcta.

### Jerga argentina

Las expresiones de varias palabras viven en la categoría `PHRASE` y llevan tono:
`de una` (sí), `ni en pedo` (no), `me copa` (preferencia), `sos un capo` (elogio),
`no cazo una` (duda), `todo bien` (saludo), `la puta madre` (insulto). El parser
las busca como frase completa antes de clasificar la oración.

Tamaño actual: **~10 KB**, 250 líneas, ~40 frases y más de 2500 formas verbales
indexadas (el número exacto lo imprime la autoprueba).

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

Actos posibles: `ORDEN`, `PREGUNTA_A_COVE`, `PREGUNTA_SOBRE_COVE`, `RESPUESTA_SI`,
`RESPUESTA_NO`, `RESPUESTA_DATO`, `BROMA`, `CORRECCION`, `SALUDO`, `PREFERENCIA`,
`SENTIMIENTO`, `ELOGIO`, `INSULTO`, `PLAN`, `CHARLA`, `DUDOSA`, `AMBIGUA`.

El léxico no solo guarda el verbo canónico: al cargar, conjuga **también los
infinitivos escondidos en los sinónimos**. Por eso `talar` (sinónimo de `cortar`)
reconoce `tala`, `talás`, `talando`; y `romper` reconoce `rompe`. Sin eso, una
orden real como "Tala estos árboles" se clasificaba como charla.

Las palabras que no se reconocen no se fingen entender: si la frase tiene dos o
más términos desconocidos y ninguna entidad ni verbo, el acto es `DUDOSA` y Cove
pide que se lo expliquen, en vez de contestar "tiene sentido".

La clasificación es composicional, no por palabra suelta:

- `PREFERENCIA` pide marca de preferencia (`gusta`, `prefiero`, `odio`) **y** primera persona.
- `SENTIMIENTO` pide una palabra de ánimo del léxico **y** primera persona (`estoy`, `tengo`, `me`).
- `ORDEN` pide imperativo real (voseo incluido), verbo solo, o infinitivo precedido por
  `a`/`que`/`de`/`para`. Un infinitivo dentro de una frase (`me gusta comer pan`) no es orden.
- Una negación sobre un verbo corto (`no cortes el árbol`) es orden negada: Cove acusa recibo y no
  la ejecuta.
- `PREGUNTA_SOBRE_COVE` (`¿cómo estás?`, `¿qué opinás?`) se responde sobre Cove, no sobre el mundo.
- Respuestas a la pregunta pendiente: sí, no, dato, vaga, `no sé` (dato sin información) o broma.

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

### Las órdenes duran

Tres reglas del mod sostienen una orden en el tiempo, porque antes morían solas:

- **`seguime` / `vení` persisten.** La tarea de seguimiento ya no se marca terminada al
  llegar a tres bloques: se detiene, espera, y vuelve a caminar cuando el jugador se
  aleja. Solo `pará`, `quedate` o una orden nueva la cancelan.
- **`mine` y `gather` pidén cantidad real.** El cerebro envía `count=32`, `radius=32` y
  `timeout_ticks=6000` (y `max_logs=64` al talar). Antes la tool usaba su valor por
  defecto de 8 bloques y parecía que "se detenía de la nada".
- **Reflejo cuidador de tareas.** Si un reflejo de supervivencia (pelear, huir, comer)
  interrumpe una tarea de recolección, el mod la reanuda hasta tres veces, 80 ticks
  después de que el reflejo termina. Una orden explícita la cancela.

Si el material no existe cerca, la tool falla y Cove lo dice: no inventa éxito.

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
| Léxico (texto) | ~10 KB |
| Clases del cerebro (7 clases) | ~70 KB de bytecode |
| JAR completo del mod | ~512 KB |

- Sin dependencias nuevas, sin descargas, sin modelos.
- Sin hilos nuevos: la comprensión y la generación corren en el hilo del agente.
- Coste por mensaje: recorrido de tokens y comparación contra ~300 formas. Microsegundos.
- Un ciclo de decisión no hace E/S: lee el estado en memoria y como mucho persiste
  `social/state.json`, que son unos pocos KB.

## 10. Correcciones de la revisión de código

Errores encontrados al revisar el motor y cómo quedaron:

1. **El historial secuestraba el chat.** El proveedor miraba si había *algún* mensaje de tool en
   la ventana; después del primer tool contestaba "dale, ya voy" a todo. Ahora solo reacciona si el
   **último** mensaje es el resultado de una tool.
2. **La pregunta pendiente no se leía en el turno normal**, así que una broma se contestaba con
   "entiendo". Ahora el turno normal también lee `Preguntaste:` / `Tema:`.
3. **Cualquier infinitivo era una orden** (`me gusta comer pan` ejecutaba `eat`). Ahora se exige
   imperativo o infinitivo introducido por preposición.
4. **Una frase larga y ajena resolvía la pregunta pendiente.** Ahora solo la resuelven datos,
   direcciones, números o una frase corta; si no, queda pendiente.
5. **Cualquier palabra desconocida con un verbo locativo era "broma"**, incluso `centro`. Ahora se
   exige nombre propio capitalizado o una palabra de la lista `ABSURD` (`marte`, `luna`, …).
6. **Los números del chat se confundían con vida y comida.** La lectura de situación ahora exige el
   bloque `## Situacion`, y el snapshot se busca después de su encabezado, no en el primer `{`.
7. **Colisión de léxico:** `cansado` estaba como sinónimo de `sueño`, así que no se reconocía el
   sentimiento. Se separaron las categorías.
8. **Un tool fallido se reportaba como éxito.** El JSON del resultado se inspecciona: si trae
   `error`, Cove dice que no le salió.
9. **La identidad no llegaba al cerebro embebido.** Ahora se leen los rasgos del prompt y se adopta
   el tono real (humor, curiosidad, iniciativa).
10. **Las negaciones ejecutaban la acción.** `no cortes el árbol` ya no llama a `chop_tree`.
11. **Un comentario después de preguntar** se tomaba como respuesta solo por ser corto; ahora la
    regla quedó explícita y cubierta por tests.
12. **Los verbos sinónimos no se conjugaban.** Solo se flexionaba el infinitivo canónico, así que
    `tala`, `rompe` o `picá` no se reconocían como órdenes. Ahora el léxico indexa todas las formas
    de todos los infinitivos de cada entrada.
13. **Faltaba vocabulario básico de la partida** (`árbol`, `rama`, `hoja`, `agua`, `fuego`, `camino`,
    `puerta`, `romper`, `talar`, `golpear`, `correr`). Sin `árbol`, "Rompe el árbol" era ruido.
14. **Planes con ranuras vacías imprimían basura**: "Por acá, en lugar". Ahora solo se eligen
    planes cuyas ranuras se pueden completar; si falta el bioma, hay variantes sin bioma.
15. **La confirmación no demostraba comprensión.** Ahora nombra la acción entendida: "dale, voy a
    talar", en vez de solo "ya voy".
16. **`seguime` terminaba al llegar.** La tarea devolvía "completada" a tres bloques, así que
    había que repetir la orden. Ahora sigue viva hasta que se la cancele.
17. **`mine` minaba ocho bloques y paraba.** El cerebro no mandaba `count`; el valor por
    defecto de la tool era 8. Ahora manda 32 bloques, radio 32 y timeout amplio.
18. **`conseguime X` ignoraba X.** El planificador no pasaba `what` a `gather`, así que siempre
    talaba madera. Ahora traduce hierro, piedra, tierra, arena, esmeralda, redstone y madera.
19. **La recolección moría cuando un reflejo la interrumpía.** Se agregó el reflejo cuidador de
    tareas, con tope de reintentos y cancelación explícita.

## 11. Límites honestos

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
  cubierto por 187 checks unitarios, no por juego real.

## 12. Cómo probar y extender

- Pruebas: `./gradlew :covellite-agent:agentTest` (187 checks).
- En juego: `/cove mind` muestra proveedor, autoridad, último motivo, pregunta
  pendiente, ánimo y rasgos.
- Extender el vocabulario: editar `covemcp/lexicon-extra.txt` en la instancia (sin recompilar).
- Extender el habla: agregar planes en `Realizer`.
- Extender la comprensión: nuevas categorías o verbos en el léxico; el parser las
  toma sin cambios.
- Extender la decisión: nuevas reglas en `OfflineBrain.decide`, siempre detrás de
  `SocialPolicy`.
