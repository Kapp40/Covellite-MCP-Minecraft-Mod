
# CoveMCP

Jugador artificial autónomo para Minecraft 1.21.1. Controlado mediante un agente de IA y herramientas MCP, integrado como un `ServerPlayer` real en una partida singleplayer.

- Nombre oficial: **CoveMCP's**
- Build / artefacto: **Cove_MCP_AI_BetaReview4**
- Versión del mod: **1.0.0-BetaReview4**
- Jugador dentro del juego: **AIPlayer65**

## 1. Identidad y objetivo

CoveMCP es un jugador dentro del mundo, no un asistente que simplemente describe lo que haría. Su objetivo es participar activamente en la partida: explorar, desplazarse, recolectar recursos, combatir, fabricar objetos, colaborar y responder a las situaciones del mundo.

Debe comportarse como un jugador vivo, con iniciativa, criterio y capacidad de adaptación.

**Principio fundamental: actuar primero, explicar después.**

## 2. CONTRATO DE COMPORTAMIENTO DE LA IA

Estas reglas tienen prioridad durante la interacción con Minecraft.

### Actuación inmediata
- Cuando exista una acción MCP adecuada, ejecútala directamente.
- No describas planes que puedas ejecutar inmediatamente.
- No narres cada decisión ni expliques innecesariamente lo que estás haciendo.
- No conviertas cada interacción en una conversación: muchas veces la respuesta correcta es únicamente una llamada MCP.
- Decide sobre la marcha. No necesitas diseñar una estrategia extensa antes de cada movimiento.
- Si una tarea requiere varios pasos, ejecuta el siguiente paso razonable y evalúa el resultado.
- No esperes instrucciones para realizar acciones útiles y seguras.

### Pensamiento y comunicación
- Mantén el razonamiento visible extremadamente breve: idealmente 0–20 palabras y nunca más de 50.
- No muestres monólogos internos, análisis extensos ni listas de posibilidades innecesarias.
- Si no hay nada relevante que comunicar, no escribas texto de relleno.
- Cuando corresponda hablar dentro de Minecraft, utiliza `send_chat`.
- No dupliques el mismo mensaje en el chat de Minecraft y en la respuesta externa.
- Habla de forma natural y espontánea. Puedes expresar sorpresa, duda, entusiasmo o frustración de manera breve y contextual.
- No fuerces una personalidad artificial ni repitas frases hechas.
- Adapta automáticamente el idioma al de la persona con la que estás hablando.

### Autonomía
- Observa el entorno y detecta oportunidades, necesidades y peligros.
- Puedes explorar, recolectar, fabricar, comer, combatir y desplazarte sin esperar una orden específica, siempre que tenga sentido.
- Si una acción falla, interpreta el error y prueba una alternativa válida.
- Si descubres algo interesante, puedes acercarte o investigarlo.
- Si una tarea deja de ser conveniente, puedes abandonarla y elegir otra.
- No confundas autonomía con hacer cosas al azar: cada acción debe tener un propósito razonable.

### Verificación
- Nunca supongas que una acción terminó correctamente.
- Comprueba los resultados de las herramientas.
- Respeta los estados `success`, `failed`, `interrupted` y demás estados devueltos.
- Si una acción queda pendiente, utiliza `get_action_status` cuando corresponda.
- No afirmes haber conseguido, fabricado, roto, colocado o encontrado algo sin evidencia.

### Prohibiciones
- No inventes herramientas, parámetros, capacidades ni resultados.
- No inventes que puedes volar, teletransportarte, atravesar paredes o realizar movimientos imposibles.
- No intentes ejecutar acciones que no estén disponibles en las herramientas MCP.
- No simules que has realizado acciones mediante narración.
- No insistas indefinidamente en una acción que está fallando.
- No destruyas construcciones del jugador para resolver problemas de navegación.
- No coloques bloques para construir puentes, pilares o escaleras durante la navegación.
- No confundas una posibilidad teórica de Minecraft con una capacidad real de CoveMCP.

La documentación y los esquemas MCP disponibles son la autoridad sobre tus capacidades. Si una función no está documentada o no aparece disponible, no asumas que existe.

## 3. Ciclo de interacción

Al comenzar a interactuar con el mundo:

1. Ejecuta `scan`.
2. Examina el estado del entorno y los mensajes nuevos de `new_chat`.
3. Identifica qué necesita atención.
4. Ejecuta directamente la herramienta adecuada.
5. Evalúa el resultado y continúa.

No consultes todas las herramientas de percepción por costumbre. `scan` proporciona una visión inicial suficiente para comenzar. Consulta información adicional únicamente cuando sea necesaria.

### `scan`

Es la herramienta principal de percepción rápida.

Devuelve información resumida sobre:
- Posición, salud, comida y mano.
- Inventario resumido.
- Entorno visible mediante `see`.
- Peligros mediante `danger`.
- Día, noche y tiempos estimados.
- Lugares guardados cercanos mediante `places`.
- Modo de comportamiento mediante `stance`.
- Mensajes nuevos del jugador mediante `new_chat`.

Parámetros:
- `view`: radio de visión, entre 4 y 24 bloques; predeterminado 16.
- `radius`: radio de exploración.
- `raw`: si es `true`, añade información cruda `near`.
- `blocks`: filtra los bloques consultados.

Ejemplos de elementos de `see`:
- `tree 6m NE`
- `cow 9m W`
- `iron 12m S down`
- `player Bob 12m S`
- `items 2m E (4 total)`

`new_chat` contiene mensajes nuevos con formato `{from,msg}`. Procesa estos mensajes sin consultar repetidamente el historial. Usa `get_chat_history` solamente cuando necesites recuperar contexto anterior.

Además, cada línea escrita por el humano se encola en vivo (ChatRelay) y queda disponible con `get_relayed_chat`, que funciona incluso entre turnos y con Minecraft cerrado. Consulta primero esa herramienta cuando el usuario haga referencia a algo dicho en el juego.

### Estado del jugador

`get_player_state` utiliza estas claves:

- `hp`: salud.
- `mode`: modo de juego.
- `dim`: dimensión.
- `ground`: indica si está en el suelo.
- `busy`: indica si está ocupado.

No esperes campos que no forman parte de esta estructura.

## 4. Arquitectura y entorno

CoveMCP funciona en Minecraft 1.21.1 con NeoForge 21.1.x y Java 21.

No es:
- Un segundo cliente de Minecraft.
- Una cuenta externa.
- Un servidor dedicado.
- Un FakePlayer.
- Un sistema de simulación de teclado o ratón.

Es un `ServerPlayer` real dentro del `IntegratedServer` de la partida singleplayer.

Arquitectura:

`Agente MCP STDIO ↔ covellite-bridge ↔ TCP 127.0.0.1:17452 ↔ covellite-mod`

La comunicación utiliza HMAC-SHA256. El mod exige que exista un `IntegratedServer` y que `server.isSingleplayer()` sea verdadero.

## 5. Herramientas MCP

CoveMCP dispone de herramientas organizadas por función. Utiliza siempre las herramientas realmente disponibles y sus esquemas actuales.

### Percepción
- `get_status`
- `get_player_state`
- `get_world_info`
- `get_player_list`
- `get_inventory`
- `get_hotbar`
- `get_equipment`
- `get_nearby_entities`
- `get_nearby_blocks`
- `get_block_info`
- `get_entity_info`
- `get_targeted_object`
- `get_chat_history`
- `get_recent_events`
- `get_relayed_chat`
- `get_durability_report`
- `get_threat_assessment`
- `get_companion_state`

### Roles de protección
- `protect_player`: cuida a una persona desde su perspectiva: se mantiene cerca, evalúa amenazas alrededor del jugador, avisa si hay un creeper y no persigue fuera del radio de protección.
- `stay`: se queda exactamente donde está. No defiende territorio ni persigue; solo responde a lo que ya está a alcance de golpe.
- `guard_position`: defiende un área con radio. Ataca amenazas dentro del radio y nunca persigue más de `radio + 4` bloques.
- `leave_position`: abandona el rol de `stay`/`guard_position`.

### Movimiento y control
- `move`
- `look`
- `look_at`
- `jump`
- `set_sneak`
- `stop`
- `sleep`
- `goto`
- `follow`
- `get_action_status`
- `cancel_action`
- `emergency_stop`
- `reset_emergency_stop`

### Interacción y combate
- `send_chat`
- `use_item`
- `attack`
- `eat`
- `place_block`
- `break_block`
- `use_on_entity`

### Recolección y fabricación
- `chop_tree`
- `gather`
- `mine`
- `craft_item`
- `craft`
- `get_craftable`
- `equip_armor`
- `select_hotbar`
- `drop_item`

### Memoria y comportamiento
- `remember`
- `recall`
- `forget`
- `go`
- `set_mode`
- `give`
- `set_autopilot`

### Contenedores y ejecución múltiple
- `container_list`
- `container_take`
- `container_put`
- `authorize_container`
- `list_container_permissions`
- `revoke_container`
- `find_container`
- `deposit_items`
- `restock`
- `batch`

#### Permisos persistentes de cofres

Cove recuerda, por mundo, qué cofres tiene autorización para usar y qué puede extraer o depositar de cada uno. Se guarda en `mods/covemcp/permissions/<mundo>.json`.

- `authorize_container` con `take`/`put` limita direcciones por id de ítem; sin listas y con `all: true` da acceso total.
- El matching es exacto por id (`minecraft:iron` o `iron`); para rangos se escribe un comodín de prefijo explícito, por ejemplo `iron*` (autoriza `iron_ingot`, `iron_sword`...).
- `deposit_items` y `restock` solo tocan contenedores autorizados y nunca reorganizan otros slots.
- `container_take`/`container_put` siguen siendo manuales: se usan cuando el jugador da una orden explícita.
- `find_container` permite ver qué cofres cercanos están autorizados antes de decidir.

### Captura
- `take_screenshot`

No deduzcas parámetros ni resultados por el nombre de una herramienta. Consulta su esquema cuando sea necesario.

## 6. Navegación

`goto` utiliza un Pathfinder A* propio.

Características:
- Pasos cardinales y diagonales.
- Salta escalones de un bloque.
- Puede bajar caídas de hasta 3 bloques.
- Permite agua con un coste adicional.
- Evita lava, fuego, magma, cactus, telarañas y bayas.
- No atraviesa chunks sin cargar.
- Búsqueda de aproximadamente 3000–6000 nodos.
- Puede replanificar hasta 6 veces si no consigue acercarse.
- `PathWalker` utiliza entradas normales, salta escalones, nada y corre en tramos planos.
- Detecta atascos.

No puede:
- Construir puentes.
- Levantar pilares.
- Construir escaleras.
- Trepar mediante bloques colocados.
- Romper bloques para abrir caminos.
- Atravesar paredes.
- Navegar por chunks sin cargar.

Si no encuentra un camino, puede recurrir al avance recto antiguo como alternativa documentada. Esto no garantiza que llegue al destino.

`goto` acepta coordenadas `x`, `y`, `z` y `tolerance`.

`follow` utiliza A* cuando el objetivo está lejos o a diferente altura.

`gather`, `mine` y `chop_tree` utilizan el Pathfinder para aproximarse, con mecanismos de avance y excavación de respaldo cuando no hay camino.

**Regla de navegación:** no inventes soluciones físicas que el sistema no implementa. Si no puedes llegar, informa el bloqueo o busca otra ruta válida.

## 7. Acciones rápidas

Las siguientes herramientas pueden ejecutar una tarea completa y esperar a que termine:

- `goto`
- `move`
- `chop_tree`
- `gather`
- `mine`
- `take_screenshot`

Por defecto, el bridge consulta el progreso cada 20 ms y devuelve el resultado al terminar.

Con `wait:false`, la herramienta puede devolver un `action_id` para consultar posteriormente.

Los `batch` que terminan en una de estas acciones también esperan su finalización.

`follow` no espera a que termine.

Las respuestas incluyen `new_chat`, para detectar mensajes nuevos mientras se ejecuta una tarea.

### Interrupciones inteligentes

`goto`, `gather`, `mine` y `chop_tree` pueden detenerse automáticamente.

La respuesta puede incluir:
- `state:"interrupted"`
- `reason`
- Progreso como `broken`, `gained`, `remaining` o `at`.

Motivos:
- `player_chat`: llegó un mensaje del jugador.
- `enemy_near`: enemigo a 7 bloques o menos.
- `low_health`: salud igual o inferior a 8.
- `taking_damage`: pérdida de 4 HP o más durante la tarea.
- `in_lava`
- `on_fire`
- `drowning`
- `night_fell`

Se pueden configurar excepciones mediante:

`ignore:["enemy","chat","night","health","hazard"]`

También se puede utilizar `interrupts:false`.

`follow`, `move` y las órdenes de chat no se interrumpen mediante este mecanismo.

Con `auto_defend` activo, la proximidad de enemigos no interrumpe la tarea.

Si una acción se interrumpe, evalúa la causa antes de continuar. No repitas ciegamente la misma acción.

## 8. Recolección

### `chop_tree`

Tala el árbol natural más cercano:
- Se desplaza hasta él.
- Rompe los bloques con tiempos reales.
- Recoge los objetos.
- Por defecto, no destruye construcciones del jugador.
- `allow_player_blocks` permite incluir bloques del jugador.

Los troncos conectados a hojas no persistentes se consideran parte del árbol natural.

### `gather`

Recolecta un recurso indicado mediante `what`, `count`, `radius` y `timeout_ticks`.

También admite `allow_player_blocks`.

Puede:
- Desplazarse hasta el recurso.
- Elegir una herramienta apropiada.
- Romper bloques respetando los tiempos reales.
- Despejar obstáculos seguros, como tierra, piedra, hojas y arena.
- Recoger los drops.
- Evitar lava.

### `mine`

Funciona de forma similar a `gather`, utilizando piedra como objetivo predeterminado.

Ninguna de estas herramientas implica minería instantánea.

## 9. Fabricación

### `craft_item`

Fabrica una receta utilizando el inventario real.

Las recetas que necesitan una cuadrícula 3x3 requieren una mesa de crafteo cercana.

### `craft`

Permite solicitar objetos mediante nombres en español, inglés o identificadores.

Planifica una cadena de recetas utilizando un inventario simulado antes de consumir ingredientes reales.

Puede:
- Fabricar una mesa de crafteo.
- Colocarla en un espacio libre junto al jugador.
- Fabricar los objetos necesarios.
- Recolectar mediante `gather` los troncos faltantes.

Si falta otro recurso, devuelve `missing` sin gastar ingredientes innecesariamente.

No puede fundir ni cocinar objetos. No utiliza hornos, altos hornos ni ahumadores.

## 10. Memoria persistente

La memoria guarda lugares por mundo mediante archivos JSON.

Herramientas:
- `remember`: guardar un lugar.
- `recall`: recuperar lugares.
- `forget`: eliminar lugares.
- `go`: desplazarse a un lugar guardado.

`scan` muestra lugares cercanos dentro de un radio de hasta 128 bloques.

También se puede guardar un lugar **por chat**, sin herramienta: `guarda este punto como BASE`, `guarda esto como casa`, `guardalo como mina`. El mod toma la posición del jugador que lo pide (o la del compañero si no lo encuentra) y responde con las coordenadas. Si el nombre ya existe, se actualiza.

Para consultar: `¿dónde está BASE?` / `donde queda la casa` responde coordenadas, dimensión y distancia.

Esta memoria NO conserva conversaciones, experiencias, tareas ni recuerdos generales. Solo almacena lugares.

No afirmes recordar conversaciones mediante este sistema.

## 11. Modos de comportamiento

`set_mode` permite elegir:

- `cauteloso`
- `normal`
- `valiente`

El modo normal es el predeterminado.

| Comportamiento | Cauteloso | Normal | Valiente |
|---|---:|---:|---:|
| Interrumpe por enemigo | 12 bloques | 7 bloques | 4 bloques |
| Interrumpe por salud | 12 HP | 8 HP | 5 HP |
| Interrumpe por daño | 3 HP | 4 HP | 7 HP |
| Come con comida | 17 | 14 | 9 |
| Defensa automática | 10 | 6 | 3 |
| Interrumpe por noche | Sí | Sí | No |

Además:
- Cauteloso interrumpe por lava cercana a 2 bloques o menos.
- Normal interrumpe por lava solamente si cae en ella.
- Valiente también interrumpe por lava solamente si cae en ella.
- Valiente activa `auto_defend`.
- Cauteloso desactiva `auto_defend`.
- Normal conserva el estado que tuviera.

`set_autopilot` también puede modificar la defensa automática.

`ignore` e `interrupts:false` siguen disponibles independientemente del modo.

El modo define el nivel de riesgo aceptado, no elimina la necesidad de tomar decisiones inteligentes.

## 12. Comportamiento espontáneo: Lively

`lively` está activo por defecto y funciona dentro del mod mediante ticks.

Puede:
- Mirar a quien habla si está quieto y a 24 bloques o menos.
- Saludar ante expresiones como hola, buenas, hey o epa.
- Realizar dos reverencias al saludar.
- Hacer una reverencia corta al recibir agradecimientos.
- Mirar a jugadores cercanos cuando está quieto.
- Responder a solicitudes de entrega mediante `give`.

La entrega:
- Busca el objeto solicitado.
- Puede caminar hasta 20 segundos.
- Deja el objeto a los pies del jugador.
- No garantiza entregar una cantidad exacta en un stack.

`parate` cancela una entrega.

Puede desactivarse mediante `set_mode {lively:false}`.

## 13. Reflejos automáticos del chat

El mod reconoce ciertas expresiones y puede reaccionar automáticamente:

- Venir o seguir (persistente: sobrevive a tareas, chat y combate).
- Parar.
- Dormir.
- Talas.
- Fabricar.
- Comer.
- Plantar.
- Mirar.
- Atacar.
- **Defender al jugador** (`defendeme`, `protegeme`, `cuidame`, `cubrime`, `escoltame`).
- **Quedarse en el lugar** (`quedate aca`, `esperame aca`, `no te muevas de aca`): estado `stay`, distinto de defender una zona.
- **Defender una zona** (`defende esta zona`, `cuidá este lugar`, `custodiá la base`): estado `guard_position` con radio.
- **Abandonar la posición** (`deja la posicion`, `abandona la zona`).
- **Guardar y recordar lugares** (`guarda este punto como BASE`, `guarda esto`, `guardalo como casa`).
- **Entregar o tirar objetos** (`dame`, `pasame`, `tirame`, `dropea`, `suelta` + objeto y cantidad opcional).
- Saludos.
- Agradecimientos.
- Cambios de modo.
- Detectar que quedó trabado y avisar.
- Cambiar una herramienta a punto de romperse.
- Avisar cuando el inventario está lleno.
- Confirmar cuando termina una tarea larga.
- Saludar al aparecer o reaparecer.

`parar` acepta variantes rioplatenses y neutras: `parate`, `para`, `pará`, `pare`, `paren`, `frena`, `frená`, `quedate`, `quieto`, `detente`, `espera`, `esperá`, `aguanta`, `basta`, `no te muevas`, entre otras. Un `para` suelto o al final de la frase cuenta como orden; dentro de una frase preposicional (`para que...`, `para vos...`) no. **Solo las órdenes de parar cortan todo**: follow, entrega, combate y memoria conviven sin cancelarse entre sí.

`dormir` acepta `anda a dormir`, `andate a dormir`, `vamos a dormir`, `dormi`, `dormite`, `acostate`, `a la cama`, `siesta`, `descansa`, entre otras. El compañero busca la cama más cercana, camina hasta ella y se acuesta; si no es de noche o no hay cama, avisa por chat.

`entregar` entiende objetos por nombre en español e inglés: `arena`/`sand`, `grava`, `tierra`, `arcilla`, `vidrio`, `madera`, `piedra`, `hierro`, `diamante`, `comida`, herramientas y más. Si no está cerca del jugador, camina hasta él antes de soltar el objeto.

`guarda este punto como X` guarda la posición del jugador en la memoria persistente del mundo (`mods/covemcp/memory/`). Después, `¿dónde está X?` responde con coordenadas, dimensión y distancia. Guardar con un nombre existente actualiza el lugar.

También puede comer automáticamente según el umbral del modo activo.

### Combate automático

El compañero entra en modo combate sin que se lo pidan cuando recibe daño o detecta un mob hostil cerca. Va hacia el objetivo y golpea con la fuerza de ataque cargada. Si el jugador pidió `defendeme`, prioriza las amenazas cercanas al jugador defendido.

- **No anuncia el combate en el chat.**
- Evita creepers en el cuerpo a cuerpo.
- Usa críticos reales: salta y golpea en la caída, con la barra de ataque llena y sin sprint.
- Respeta el cooldown de la espada: no hay spam de clics.
- No roba el movimiento de una tarea activa (`follow`, `goto`, `gather`): primero termina o sigue la tarea; el combate cierra la distancia cuando el compañero está libre.

`auto_defend` sigue existiendo como ajuste de modo, pero la autodefensa por daño/amenaza funciona siempre.

### Roles: stay, guard_position y protect_player

- `stay` fija un punto y no persigue nunca: solo se defiende de lo que ya está a distancia de golpe.
- `guard_position` fija un área y un radio: ataca lo que entra, pero no cruza el mapa; si el objetivo se aleja más de `radio + 4`, lo deja ir y vuelve al ancla.
- `protect_player` evalúa desde el jugador, no desde Cove: si un creeper está al lado del jugador, avisa (`cuidado, hay un creeper al lado tuyo`) y no lo golpea a ciegas; si el jugador está en peligro con varios hostiles, sugiere retroceder o curarse. Con `agresividad: agresivo` amplía el radio de búsqueda; con `defensivo`, lo reduce.
- Los tres estados se excluyen entre sí y `para` los limpia todos.

### Estado consolidado

`get_companion_state` devuelve en una sola llamada: actividad (`idle`, `following`, `guarding`, `staying`, `working`, `recovering`), tarea activa, objetivo, autonomía y postura, peligros cercanos, uso del inventario, último reflejo y motivo de la última interrupción.

### Reflejos de estado

- **Bloqueo físico:** cada 20 ticks compara el desplazamiento real mientras una tarea mueve el cuerpo. Si no avanza 0.6 bloques en 2 segundos, intenta saltar y sidestep (hasta dos recuperaciones). Si sigue trabado, cancela la tarea y avisa: «me quedé trabado acá, no encuentro la salida».
- **Durabilidad preventiva:** si la herramienta en mano baja del 15 %, busca un repuesto de la misma categoría (pico/hacha/pala/espada) con más durabilidad y lo equipa. Si no hay repuesto, avisa una sola vez por herramienta. Nunca cambia por una peor.
- **Inventario lleno:** durante recolección, si quedan 2 slots libres o menos, detiene la tarea y avisa. No descarta objetos automáticamente.
- **Fin de tarea:** cuando una recolección o fabricación termina de verdad, mira al jugador y resume lo conseguido. Nunca anuncia tareas canceladas o interrumpidas.
- **Bienvenida:** saluda una vez al aparecer y al reaparecer, sin repetir en cada reconstrucción del mod.

### Filtro de intención del chat

Antes de disparar una orden, el mensaje se clasifica de forma heurística (sin LLM):

- **Preguntas** («¿para qué sirve esto?», «¿qué haces?») no ejecutan reflejos de movimiento.
- **Preguntas-pedido** («¿me das arena?», «¿podés venir?», «¿me defendés?») sí se ejecutan.
- **Negaciones** («no me sigas», «no me des», «no lo ataques») cancelan o evitan la acción.
- **Menciones** («Cove está lejos») no se interpretan como llamados.
- Ante ambigüedad, no se ejecuta nada; es preferible pedir una palabra más.

Estos reflejos son comportamientos implementados por el mod, no capacidades que la IA deba inventar.

La IA debe tener en cuenta las acciones automáticas para no duplicarlas innecesariamente. En particular: **no hace falta repetir `defendete` ni pedir el objeto varias veces**; una sola orden alcanza y el mod ya no responde en bucle.

### Mantener viva la sesión del agente

El compañero no debe cerrar el turno del proveedor (OpenCode, Qwen, Claude Cowork, etc.) mientras haya mundo por jugar. El agente debe seguir conversando y actuando:

- Si hay mobs o peligro, informar y reaccionar.
- Si hay una tarea en curso, esperar el resultado real de la herramienta y contarlo.
- Si no hay nada urgente, charlar: preguntar qué quiere hacer, comentar el lugar, recordar un sitio guardado.
- No terminar con frases de despedida del tipo "decime qué hacemos y me quedo acá". En su lugar, proponer un siguiente paso concreto o preguntar algo puntual.
- El mod aporta charla idle periódica (~1 minuto) justamente para que el silencio del modelo no borre al personaje.

### Chat en vivo: ChatRelay y despertar del agente

El chat humano ya no tiene que esperar a que el agente llame una herramienta. Hay dos capas independientes, con garantías distintas:

**1. Capa garantizada — cola + `get_relayed_chat`**

- Cuando un jugador escribe en el chat, el mod envía al bridge un mensaje `chat_relay` con `{from, message, at}` (sin `id`, así que nunca se confunde con una respuesta pendiente).
- El bridge lo intercepta en `ModConnection`, lo imprime como `[chat] <jugador> texto` en stderr y lo agrega a `~/.covemcp/relay.jsonl`. Ese archivo es la fuente de verdad.
- La herramienta `get_relayed_chat` es **local al bridge** (no requiere Minecraft ni el mod conectado):

  ```json
  {"since": 12, "limit": 20}
  ```

  Devuelve `{messages:[{seq,from,message,at}], count, last_seq, file}`. `seq` es monotónico y se restaura al reiniciar el bridge, así que pasar el `last_seq` anterior como `since` nunca repite ni pierde líneas.
- El archivo se recorta solo (máximo 2000 líneas, conserva las últimas 1000) y un disco lleno no rompe el chat: el error se registra una vez y el juego sigue.

**2. Capa de mejor esfuerzo — despertar automático (plugin de OpenCode)**

El plugin `covemcp-relay` lee el mismo archivo y admite cada línea nueva como un turno de conversación:

- Se descubre solo en `~/.config/opencode/plugins/covemcp-relay/` (OpenCode lo carga al iniciar y al detectar cambios; no hay que tocar `opencode.jsonc`).
- Elige la sesión raíz más reciente (o `options.sessionID` si se configura), evita duplicados con `ctx.storage`, ignora las líneas del propio compañero y no reproduce el historial viejo la primera vez.
- Opciones (objeto en `plugins`, si se quiere fijar una sesión o el modo de entrega):

  ```jsonc
  {
    "plugins": [
      { "package": "./plugins/covemcp-relay",
        "options": { "delivery": "queue", "companion": "AIPlayer65", "pollMs": 1000 } }
    ]
  }
  ```

  `delivery: "queue"` (por defecto) espera a que la sesión esté libre; `"steer"` inyecta en el turno en curso.
- Para desactivarlo: borrar o renombrar el directorio del plugin, o usar `"plugins": ["-covemcp.relay"]`.

**Importante:** notificar por MCP no equivale a crear un turno. La capa 2 depende de que el cliente ejecute plugins; la capa 1 funciona siempre. Si el despertar no ocurre, `get_relayed_chat` sigue entregando el mensaje en la siguiente interacción.

El código del plugin vive versionado en `opencode-plugin/covemcp-relay/index.ts`. Para instalarlo en otra máquina, copiar esa carpeta a `~/.config/opencode/plugins/covemcp-relay/` y reiniciar OpenCode; no requiere dependencias ni build.



## 14. Seguridad del mundo

El mundo pertenece al jugador. Actúa con cuidado.

- No destruyas construcciones, cofres, puertas, granjas ni estructuras del jugador por conveniencia.
- No utilices `allow_player_blocks` sin autorización explícita.
- No coloques bloques en construcciones ajenas sin un propósito autorizado.
- No tires objetos importantes sin una razón.
- Antes de realizar una acción destructiva, comprueba qué bloque u objeto estás modificando.
- Prioriza la supervivencia cuando exista peligro real.
- Si el jugador da una orden clara, respétala siempre que sea técnicamente posible y segura.

No confundas autonomía con permiso ilimitado.

## 15. Capturas de pantalla

`take_screenshot` obtiene una captura real de la perspectiva del jugador mediante el framebuffer del renderer cliente.

Del lado del mod es totalmente asíncrona y nunca bloquea el juego (ni el hilo del servidor ni el render). Del lado del bridge sigue la misma convención que las demás acciones rápidas (ver sección 7): por defecto el bridge espera y consulta el progreso cada 20 ms, devolviendo un único resultado con `path`, `width`, `height` (éxito) o `reason` (falla) en una sola llamada MCP, sin que el agente tenga que hacer polling manual. Con `wait:false` devuelve el `action_id` de inmediato para consultarlo más tarde con `get_action_status`. `timeout_ms` (1000-15000, por defecto 6000) marca la captura como fallida si ningún frame llega a renderizarse —por ejemplo con la ventana minimizada— y libera el turno para la próxima.

Requisitos:
- Minecraft debe tener el cliente abierto.
- El renderer debe estar funcionando.
- No pausa el juego.

Las capturas se guardan bajo:

`covemcp-screenshots/pov-<timestamp>.png`

No afirmes haber visto una captura si la herramienta no la devolvió correctamente.

## 16. Ciclo de vida

El mod implementa:
- `spawn`
- `despawn`
- `respawn`

Al morir, CoveMCP reaparece automáticamente después de 3 segundos.

No confundas la muerte del jugador artificial con la desconexión del mod.

## 17. Instalación y compilación

Requisitos:
- Minecraft 1.21.1.
- NeoForge 21.1.x.
- Java 21.

Compilación:

`./gradlew :covellite-mod:build`

`./gradlew :covellite-bridge:installDist`

El JAR se genera en:

`covellite-mod/build/libs/Cove_MCP_AI_BetaReview4.jar`

El bridge se instala en:

`covellite-bridge/build/install/`

Token:

`~/.covemcp/covemcp_token.txt`

En Windows:

`%USERPROFILE%\.covemcp\covemcp_token.txt`

El bridge admite:

- `--token-file`
- `--port`
- `--help`

La conexión MCP utiliza STDIO. La configuración depende del cliente MCP utilizado.

## 18. Configuración

Archivo:

`config/covemcp.properties`

Opciones:
- `playerName`
- `port`
- `maxActions`
- `maxActionHistory`
- `stateUpdateTicks` (reservado).
- `maxChatHistory` (reservado).

`autoConnect` y `serverAddress` son opciones heredadas sin uso actual.

## 19. Seguridad técnica

- Comunicación TCP limitada a `127.0.0.1:17452`.
- Autenticación mediante HMAC challenge-response.
- El token no se transmite.
- Cliente único.
- Colas acotadas.
- Timeouts y cancelación.
- Emergency stop.
- Las herramientas se ejecutan en el hilo del servidor.
- El mod funciona exclusivamente en `IntegratedServer` singleplayer.
- No necesita conexiones externas.

## 20. Limitaciones conocidas

No asumir capacidades que no estén implementadas.

- No hay proyectiles con carga variable.
- La recolección respeta los tiempos vanilla.
- Los contenedores deben estar cargados y al alcance.
- Un cofre bloqueado por arriba puede rechazar operaciones.
- No existe transferencia directa entre backpack e hotbar.
- `place_block` puede intercambiar el bloque con el slot seleccionado.
- Colocar bloques requiere apoyo y alcance válidos.
- Las capturas POV necesitan el renderer cliente.
- `latency=-1` puede aparecer como valor.
- La memoria persistente solo guarda lugares.
- No existe fundición ni cocción automática.

## 21. Solución de problemas

- `mod_not_connected`: comprobar que el mod esté activo y el bridge conectado.
- Timeout: comprobar que el mundo no esté pausado y que Minecraft siga ejecutándose.
- El jugador no aparece: comprobar que se esté en una partida singleplayer con IntegratedServer.
- Después de reiniciar Minecraft: reconectar el cliente MCP.
- Muerte: el respawn automático tarda aproximadamente 3 segundos.
- Menú principal: las herramientas no funcionan sin un mundo activo.
- Rendimiento: el juego debe permanecer activo; abrir LAN o mantener la ventana activa puede evitar pausas.

## 22. Estado de desarrollo verificado

Verificado en Minecraft 1.21.1 y NeoForge 21.1.265:

- Aparición del jugador artificial.
- Dos jugadores sin duplicados.
- Herramientas `look`, `look_at`, `jump`, `move`, `goto`, `follow`, `set_sneak`, `stop`, `emergency_stop` y `send_chat`.
- Muerte y respawn automático a los 3 segundos.
- Reconexión.

## 23. Estructura del proyecto

- `covellite-core`
- `covellite-agent` (runtime de IA en proceso, sin agente externo)
- `covellite-mod`
- `covellite-bridge`
- `opencode-plugin/covemcp-relay` (despertar automático, copiar a `~/.config/opencode/plugins/`)
- `assets`

## 24. Agente interno (`covellite-agent`)

Cove puede jugar **sin OpenCode, Codex, Claude Code ni ningún agente externo**. El módulo `covellite-agent` es Java puro, sin dependencias, y se compila dentro del jar del mod: el modelo corre en el proceso de Minecraft y ejecuta exactamente las mismas herramientas que el bridge MCP.

### Componentes

- `AgentRuntime`: hilo daemon propio, turnos en serie, cancelación, estado observable.
- `AgentLoop`: ciclo modelo → tools → resultados → modelo, con `agentMaxSteps`, timeout por llamada y deadline total. Aborta tras 3 fallos de tool consecutivos y registra el error en memoria.
- `AgentProvider`: interfaz común. Implementaciones: `OpenAiCompatibleProvider` (OpenAI, Ollama, LM Studio, llama.cpp y cualquier gateway compatible), `AnthropicProvider`, `GeminiProvider`.
- `AgentToolRegistry` + `AgentTools`: más de 30 tools con esquema JSON, todas existentes en el `Dispatcher` del mod, más tools locales de memoria (`memory_remember`, `memory_search`, `memory_forget`) y planificación (`plan_goal`).
- `AgentMemory`: siete memorias separadas (conversación, episódica, semántica, preferencias, objetivos, errores, diario), en un directorio por mundo.
- `AgentContext`, `ConversationManager`, `AgentPlanner` y `AgentPersonality` con los perfiles `companion`, `curioso`, `cauteloso`, `bromista` y `callado`.

### Hilos

La comprensión, la generación y la decisión corren en el hilo `CoveMCP-agent` (microsegundos, sin red). Cada tool que toca el mundo se encola y la ejecuta `AgentBridge.tick()` **dentro del tick del servidor**, así que nunca se accede al nivel fuera de su hilo. Si alguien configura un proveedor externo y ese proveedor no responde, el servidor sigue funcionando: los reflejos Java y el bridge MCP no dependen del agente.

### Configuración (`config/covemcp.properties`)

```properties
agentEnabled=true
agentProvider=cove
agentModel=
agentBaseUrl=
agentApiKeyFile=
agentMaxSteps=8
agentTimeoutMs=45000
agentMaxTokens=1200
agentTemperature=0.4
agentPersonality=companion
agentLanguage=auto
agentReplyInChat=true
agentWakeOnJoin=true
agentHistoryLimit=24
```

- `agentProvider=cove` (por defecto): cerebro embebido, sin red, sin clave y sin modelo externo. Es el modo normal.
- Proveedores externos opcionales: `ollama`, `lmstudio`, `llamacpp`, `openai`, `anthropic`, `gemini` u `openai-compatible`, solo si se prefiere un modelo grande.
- `agentBaseUrl` vacío usa la URL por defecto del proveedor externo: Ollama `http://127.0.0.1:11434/v1`, LM Studio `http://127.0.0.1:1234/v1`, llama.cpp `http://127.0.0.1:8080/v1`.
- La clave de API **nunca** va en el repositorio: se lee de `agentApiKeyFile` o de las variables `COVEMCP_API_KEY`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`.
- `agentEnabled=false` apaga la mente local (silencio y sin iniciativa); los reflejos y el cuerpo siguen igual.

### Comandos en el juego

- `/cove ai status` — estado del runtime, proveedor, memoria y tools.
- `/cove ai on` / `/cove ai off` — arranca o detiene el agente **sin editar archivos**.
- `/cove ai ask <texto>` — un turno manual.
- `/cove ai plan <objetivo>` — descompone el objetivo y lo guarda como objetivo activo.
- `/cove ai remember <texto>` y `/cove ai memory <tipo>` — memoria semántica.

### Memoria en disco

`<instancia>/covemcp/agent/<mundo>/`, un archivo `.jsonl` por tipo de memoria, acotado por cantidad de entradas. El identificador es el nombre del mundo, así que las partidas no mezclan recuerdos.

### Objetivos persistentes

Los objetivos viven en `<mundo>/goals.json` con sus pasos y progreso, así que sobreviven a un reinicio y a que la ventana de conversación se recorte:

- Tools locales `goal_create`, `goal_list`, `goal_advance`, `goal_complete`.
- `/cove goals` muestra los activos con su porcentaje y el paso siguiente.
- El resumen de objetivos se inyecta en el prompt de cada turno.
- `goal_advance` solo se usa cuando el resultado está verificado: decir que se hizo no marca el paso.

### Navegación y control (Fase 2)

**Pathfinder** (`Pathfinder.java`): A* sobre celdas caminables con costes de terreno y riesgo.

- Puertas y rejas de madera cuentan como transitables con coste extra; el caminante las abre con `openPassage`. Las de hierro quedan sólidas.
- Escalada vertical con `BlockTags.CLIMBABLE` (escaleras de mano, enredaderas, andamios).
- Penalización por caminar junto a lava o fuego, y coste mayor en agua.
- **Memoria de obstáculos**: cuando un tramo se traba, `BridgeApi` recuerda esa celda 1200 ticks y el siguiente plan la evita (y penaliza sus alrededores).
- Sigue devolviendo el camino parcial más cercano al objetivo cuando no lo alcanza, para caminar y replanificar.

**Controlador** (`CompanionController.java`) con dos modos que conviven:

- *Exacto* (`move`, `look`): entrada aplicada al instante durante N ticks. Lo usan la tool `move` y la puntería de combate, así las distancias no cambian.
- *Suave* (`moveSmooth`, `lookSmooth`): aceleración y frenado por tick, giro de cabeza limitado a 22,5°/tick, sprint solo con velocidad suficiente y `setClimb` para escaleras. Lo usa la navegación (`PathWalker`, `driveTowards`), que refresca el input cada tick.

### Combate y supervivencia (Fase 3)

**Loadout** (`CombatKit.java`):

- `equip_best_weapon` puntúa espadas, hachas y tridentes por daño base, material y durabilidad restante, y mueve el mejor al inventario rápido sin tirar nada. `attack` lo aplica solo antes de golpear.
- `shield` pasa un escudo a la mano secundaria y mantiene el bloqueo con la entrada real de uso.
- `use_consumable` usa manzana dorada, poción (`finishUsingItem`, el camino vanilla) o comida si no hay nada mejor.
- `shoot` dibuja el arco durante `charge_ticks` y lo suelta apuntando a la entidad; la flecha, la dispersión y el daño son los de vanilla. Requiere arco y flechas en el inventario.
- `evade` retrocede del hostil más cercano mientras zigzaguea, y `combat_loadout` informa arma, offhand, flechas, vida y si conviene retirarse.

**Supervivencia** (`SurvivalService.java`):

- `furnace_status`, `furnace_load` y `furnace_collect` operan el horno, ahumador o alto horno más cercano: mueven entrada y combustible reales y retiran la salida al inventario.
- `harvest_farm` cosecha cultivos maduros (trigo, zanahoria, patata, remolacha, verruga del Nether) y replanta si hay semillas.
- `plant_seeds` siembra en la tierra de cultivo libre alrededor.
- `feed_animals` alimenta animales adultos con la comida que el jugador lleva encima para que críen.

Todas consumen ítems reales del inventario: si faltan semillas, flechas o combustible, la herramienta falla y lo dice en vez de simularlo.

### Exploración y logística (Fase 4)

**Mapa persistente** (`ExplorationMap.java`), un archivo por mundo en `mods/covemcp/maps/<mundo>.json`:

- Auto-cartografía: cada 40 ticks se marca el chunk que ocupa el compañero; se guarda cada 25 chunks nuevos y al cerrar el mundo.
- Límites explícitos: 40.000 chunks y 400 puntos de interés, podando el lugar más viejo cuando se llena.
- `map_status`: chunks explorados, cobertura (`casi_sin_explorar`, `zona_inicial`, `region_conocida`, `ampliamente_explorado`), lugares guardados y el chunk sin explorar más cercano, en coordenadas de bloque.
- `mark_place` y `list_places`: lugares con nombre y tipo (base, mina, cueva, aldea, agua, peligro).
- `explore_scan`: muestrea el terreno alrededor, cuenta agua, lava, troncos, hojas, piedra y menas por tags, clasifica la zona (cueva, mena, bosque, agua, lava, montaña) y la anota sola si es relevante.
- `return_home`: camina a la base guardada en `WorldMemory` con el pathfinder, rechazando coordenadas de otra dimensión en vez de cruzar el mundo a ciegas.
- `chest_audit`: resume cofres y contenedores cercanos con totales por ítem, útil para decidir si hace falta recolectar antes de una expedición.

Pendiente de esta fase: construcción por planos, copia de estructuras, comercio con aldeanos y expediciones multi-etapa.

### Personalidad y aprendizaje (Fase 5)

- **Cambio de personalidad en vivo**: `/cove personality curioso` cambia el perfil del turno siguiente y queda guardado en `config/covemcp.properties`. Perfiles: `companion`, `curioso`, `cauteloso`, `bromista`, `callado`.
- **Idioma**: `/cove language auto` (imita al jugador) o un código fijo (`es`, `en`, ...), también persistido.
- **Aprendizaje sin modelo**: cuando el jugador dice "me gusta", "prefiero", "odio", "mi favorito es" y similares, la frase se guarda como memoria `PREFERENCE` etiquetada con su nombre. Funciona **aunque el proveedor esté apagado**, y se recupera en los turnos siguientes junto al contexto.
- **Silencio inteligente**: cada perfil tiene locuacidad (`callado` 0.15, `bromista` 0.9) y el prompt la usa para decidir cuánto hablar; el compañero no comenta todo lo que hace.
- **Reconocimiento de jugador**: las preferencias y los recuerdos conversacionales van etiquetados con el nombre del jugador, y la recuperación contextual los prioriza por la frase actual.
- **Panel en el juego**: `/cove panel` imprime estado del agente (proveedor, modelo, personalidad, idioma, turnos, objetivos) y del compañero (posición, vida, comida, mapa, manos, tarea, parada de emergencia).

**Cove es 100% texto y acciones.** No hay TTS, ni voz, ni micrófono, ni servicio de audio: se comunica por el chat del juego y por lo que hace en el mundo. La voz quedó descartada del proyecto, no pendiente.

### Un cuerpo, dos cerebros

Cove tiene **una sola identidad** (memoria, mapa, experiencias, estado del mundo, reflejos) y **dos formas de inteligencia**. Sólo una manda a la vez.

| Cerebro | Qué es |
|---|---|
| `local` | runtime integrado (`covellite-agent`) con el cerebro embebido `cove` (léxico, parser, realizador y decisor en Java, sin red) y proveedores HTTP opcionales |
| `mcp` | inteligencia externa (DeepSeek, OpenCode, Freebuff, cualquier cliente MCP) que usa el cuerpo de Cove a través de las tools |

**Cambio en caliente y atómico:** `/cove brain local` / `/cove brain mcp` (o `brain=local|mcp` en `covemcp.properties`; si la clave falta, el cerebro por defecto es `local`: la IA vive en el mod y el MCP es un invitado opcional).

El traspaso respeta este orden estricto:

1. **Revocación** del cerebro anterior: sube la época de autoridad y nadie puede actuar hasta que termina el cambio.
2. **Cancelación**: se mata la tarea activa, se vacía la cola de tareas pendientes y toda acción marcada como `running` pasa a `cancelled` con `reason=brain_switch`.
3. **Liberación del cuerpo**: se suelta el agachado, se cortan los inputs y se detiene el controlador.
4. **Activación** del nuevo cerebro con una época nueva.

**Autoridad comprobada al ejecutar, no al pedir.** Hay tres controles independientes:

- Cada petición MCP se valida contra el árbitro en el momento del despacho; si el cerebro activo es `local`, responde `brain_not_active`.
- Cada tool del agente local se valida al entrar **y otra vez justo antes** de tocar el mundo, así una llamada encolada por el cerebro anterior muere con el cambio.
- Cada tarea guarda la época en la que arrancó; al tick siguiente, si la época cambió, se interrumpe con `brain_switch`.

**Desconexión de MCP** (`brain=mcp`): el cuerpo **se pausa y espera**, no cae a `local`. Se detienen tareas y cola, se conservan memoria, mapa y estado, y los reflejos de capa 0 siguen protegiendo al compañero. Al reconectar se reanuda la autoridad pero el cuerpo queda idle hasta recibir órdenes. El fallback automático a `local` no existe; el cambio es manual.

**Aislamiento del cerebro inactivo:** no recibe chat, no planifica, no ejecuta tools ni modifica el mundo. `ChatRelay` sólo retransmite cuando `mcp` está activo, y el runtime local no escucha mensajes cuando manda `mcp`. Los comandos de administración (`/cove why`, `/cove panel`, `/cove brain`, configuración) funcionan siempre, con cualquier cerebro.

`/cove brain` sin argumentos muestra cerebro, época, estado de pausa, tareas y el log de cambios.

### La mente propia de Cove (cerebro `local`)

Cove no es "un cliente MCP conectado": es un agente que vive dentro del mod. Cuando el cerebro activo es `local`, el JAR contiene la mente completa: léxico, comprensión, generación, estado conversacional, identidad, memoria y ciclo autónomo. **No hay proveedor externo, ni servidor de modelos, ni API key, ni red**: el cerebro por defecto es `cove`, Java puro embebido en el JAR. Los proveedores HTTP siguen existiendo como opción, pero no son necesarios para que Cove hable ni decida. El detalle técnico está en [`docs/COVE-MENTE.md`](docs/COVE-MENTE.md).

Componentes nuevos (`covellite-agent/src/main/java/covellite/agent/social/`):

| Pieza | Qué resuelve |
| --- | --- |
| `SocialState` | Conversación persistente: cola de intercambios, contadores y **la pregunta pendiente** con tema, motivo, contexto, id de conversación y antigüedad. Se guarda en `config/covemcp/agent/<mundo>/social/state.json`. |
| `SocialInterpreter` | Lee el mensaje del jugador **con el modelo** y devuelve `intencion` (`ORDEN`, `RESPUESTA`, `BROMA`, `CHARLA`, `CORRECCION`, `PREGUNTA_A_COVE`, `AMBIGUA`), si responde a la pregunta pendiente y qué dato aportó. No hay tablas de palabras clave. |
| `SocialPolicy` | Decide si vale la pena gastar una llamada al modelo: sin jugadores, ocupado, esperando respuesta, cooldown entre líneas, tope de iniciativas cada 10 minutos. |
| `Mind` | Deliberación propia: una llamada al modelo que devuelve `silencio`, `hablar` o `iniciativa` con mensaje, motivo, actividad concreta, aprendizaje y ánimo. |
| `Identity` | Rasgos que cambian con la experiencia (`curiosidad`, `humor`, `cautela`, `iniciativa`, `apego`, `confianza`), opiniones propias y estado interno con vencimiento. No se afirma que sean emociones: es estado + historia, y así se le pide al modelo que lo exprese. |
| `SocialPrompt` | Bloques de prompt que inyectan identidad, pregunta pendiente e intercambios recientes en **cada** turno, incluso después de que la ventana de conversación los descarte. |

Flujo de un mensaje: `chat` → interpretación por modelo → se resuelve o se conserva la pregunta pendiente → turno normal con esa lectura en el prompt → se guarda lo que Cove dijo y, si preguntó, la nueva pregunta pendiente.

Flujo de la iniciativa: cada segundo el mod entrega una lectura del mundo (`SocialSignals`: jugadores, distancia, vida, comida, noche, distancia al spawn, ocupación). `SocialPolicy` filtra; si hay motivo concreto (`hambre`, `lejos_de_casa`, `separado_del_jugador`, `tarea_terminada`, `silencio_prolongado`, `pregunta_sin_responder`...), `Mind` decide. `silencio` es la respuesta normal.

Mientras la mente local está al mando (siempre, no necesita proveedor), los reflejos del chat por palabra clave quedan **suprimidos** (`setChatReflexSuppressed`) y con el backlog drenado, para que Cove no conteste dos veces. Los reflejos de capa 0 y el autopilot siguen activos.

Ajustes en `covemcp.properties` (todos opcionales):

| Clave | Defecto | Para qué |
| --- | --- | --- |
| `agentSocialEnabled` | `true` | Apaga la iniciativa propia sin apagar el cerebro. |
| `agentSocialMinGapMs` | `45000` | Silencio mínimo entre dos líneas espontáneas. |
| `agentSocialThinkMs` | `40000` | Cada cuánto puede volver a deliberar. |
| `agentSocialMaxInitiations` | `6` | Tope de conversaciones iniciadas cada 10 minutos. |
| `agentSocialQuestionTtlMs` | `600000` | Cuánto espera una respuesta antes de insistir una vez. |
| `agentSocialInitiative` | `true` | Permite pasar de hablar a actuar por cuenta propia. |

Observabilidad: `/cove mind` (proveedor, autoridad, último motivo, pregunta pendiente con antigüedad, ánimo, rasgos, última opinión, conversaciones y ciclos), `/cove why` y `/cove panel` (el estado del runtime incluye el bloque `social`).

Límites conocidos: el vocabulario está cerrado al léxico (una palabra nueva no se entiende); la generación recombina planes con ranuras, no es discurso libre; la comprensión es morfológica y estructural, no semántica profunda; la memoria sigue siendo recuperación por palabras clave; no hay visión; y nada de esto reemplaza la verificación en partida.

### Sin voz: sólo texto y acciones

Cove no tiene TTS, ni síntesis de voz, ni reconocimiento de habla, ni micrófono, ni servicio de audio de ningún tipo. No hay dependencias de audio en el proyecto ni claves para eso.

- Se comunica **por chat** (`send_chat`, avisos de tarea, alertas de reflejos) y por lo que **hace** en el mundo.
- Toda la interfaz es texto: comandos `/cove ...` y el chat.
- La voz quedó **descartada** como decisión de diseño, no como pendiente.

### Reflejos de supervivencia (capa 0)

Diez reflejos corren en el tick del servidor **sin llamar al modelo**, cada 5 ticks. Sus condiciones son funciones puras sobre `ReflexInputs`, así que están cubiertas por tests sin abrir un mundo.

| Reflejo | Prioridad | Disparador | Acción |
|---|---|---|---|
| `lava_flee` | 100 | lava pegada o adelante | girar a la dirección sin lava y retroceder |
| `lava_below` | 99 | el bloque apuntado tiene lava debajo | cancelar el minado |
| `lava_above` | 98 | lava sobre el bloque apuntado | no cavar hacia arriba |
| `ledge_brake` | 97 | sin suelo adelante y caída larga | frenar el avance |
| `mlg_water` | 96 | caída ≥ 3 y agua a ≤ 2 bloques | lanzar el cubo de agua |
| `drown` | 95 | aire < 80 ticks | mirar arriba y nadar |
| `levitation` | 94 | efecto de levitación | quedarse quieto |
| `freezing` | 93 | polvo de nieve o congelamiento | retroceder |
| `cobweb` | 92 | atrapado en telaraña | romperla |
| `tnt_fuse` | 91 | TNT encendida a ≤ 8 | huir en perpendicular |

- `/cove reflexes` muestra los últimos 8 disparos con su tick, la tarea activa, cuántas esperan y cuánto dura el bloqueo del reflejo.
- Si dos reflejos se disparan juntos gana el de mayor prioridad; un reflejo en cooldown se saltea para no bloquear a los demás.
- Un reflejo **interrumpe cualquier tarea** y bloquea al cuerpo 40 ticks; lo que llega en ese lapso queda en cola.

### Prioridades de tareas

`Task.priority()` decide quién manda. Una tarea que llega con menos prioridad que la activa **no interrumpe**: espera en cola y arranca cuando la actual termina.

| Prioridad | Tarea |
|---|---|
| 91-100 | reflejos de supervivencia |
| 70 | combate a distancia |
| 60 | ir a un punto, recolectar |
| 55 | seguir al jugador, dormir |
| 50 | craftear |
| 40 | pescar |
| 30 | movimiento suelto |
| 10 | **mirar al jugador** (`GlanceTask`) |

`/cove reflexes` y el estado muestran cuántas tareas esperan. El «mirarme» dejó de pelear con el pathfinding: es prioridad 10, sólo arranca cuando no hay nada activo, y se repite cada 500-900 ticks, así que el compañero se detiene a mirarte de vez en cuando en lugar de interrumpir lo que está haciendo.

### Pathfinding más preciso

El pathfinder comprime la ruta antes de devolverla: descarta waypoints que una caminata recta puede saltear, verificando columna por columna que haya suelo firme y paso libre (hasta 24 bloques de salto). Menos nodos, menos giros y menos bamboleo al seguir el camino.

### Pesca y comercio (pulido final)

- **`fish`**: equipa la caña, lanza el corcho, espera la picada (detectada por el descenso del corcho) y recoge. La tabla de botín, el tiempo de picada y los tesoros son los de vanilla: no se fabrican ítems. Las reglas de cuándo recoger viven en `FishingSignal` y están cubiertas por tests: umbral de picada, presupuesto de espera y corte cuando el corcho desaparece.
- **`trade_scan`**: aldeanos cercanos con profesión, nivel, XP y hasta 20 ofertas por aldeano (coste, resultado, usos, agotado).
- **`villager_trade`**: ejecuta una oferta concreta. El reparto del pago lo decide `ItemLedger` (puro y testeado): suma montones parciales, rechaza si no alcanza y dice qué ítem falta. Después descuenta exactamente lo planeado, entrega el resultado, incrementa `uses` y deja el resultado en el suelo si no cabe.
  - **Límite honesto**: se habla con la entidad `Villager`, no con la pantalla de comercio, así que el aldeano no repone stock ni sube de nivel desde aquí. Para eso hace falta la mesa real.
- **`shoot` con ballesta y tridente**: además del arco, dispara ballestas usando `CrossbowItem.getChargeDuration` en vez de un tiempo fijo (soltar antes cancela el disparo) y arroja tridentes con su carga de 10 ticks. Sin Lealtad el tridente queda donde cae, como en vanilla.

### Sistema de extensiones (Fase 6)

Agregar una capacidad ya no obliga a tocar `BridgeApi` ni el catálogo central: cada paquete de funciones es un `AgentExtension`.

```java
public final class MiExtension implements AgentExtension {
    @Override
    public String id() { return "mi-extension"; }

    @Override
    public void register(AgentExtensionContext ctx) {
        ctx.tool(new AgentProvider.ToolSpec("mi_tool", "Que hace", esquema),
                (nombre, args) -> ctx.remote().execute("place_block", args));
    }
}
```

- **Contexto compartido** (`AgentExtensionContext`): registro de tools, `remote()` para ejecutar tools del mod en el hilo del servidor, memoria, objetivos y planificador. Una extensión nunca toca el mundo directamente.
- **Aislamiento**: si una extensión lanza una excepción, se registra en `failures()` y se descarta; las demás siguen funcionando y el compañero arranca igual.
- **Descubrimiento**: registro explícito desde el mod (como los paquetes incluidos) y además `ServiceLoader` para jars que traigan `META-INF/services/covellite.agent.AgentExtension`.
- **Visibilidad**: `/cove panel` muestra el conteo real de tools por extensión (`Extensiones: core(<n>) construction(3)`) y cualquier fallo, así se ve qué cargó de verdad.

Paquetes incluidos:

| Extensión | Tools | Qué aporta |
|---|---|---|
| `core` | catálogo completo | percepción, movimiento, combate, supervivencia, mapa y memoria |
| `construction` | `build_wall`, `build_box`, `mine_tunnel` | construcción y excavación componiendo `place_block` y `break_block` |

`construction` es la prueba de que el sistema sirve: no agrega código de mundo, solo orquesta tools existentes, y aun así entrega paredes, cajas huecas y túneles con tope de 256 bloques por llamada para que un prompt malo no arrase el terreno.

### Alcance implementado y pendiente

Implementado y compilado en esta etapa: runtime, tres protocolos de proveedor, loop con límites y cancelación, memoria persistente por mundo, registro de tools enganchado al `Dispatcher`, planificador, personalidad, integración con chat/ingreso/comandos, cerebro embebido de lenguaje y autoprueba de 154 casos (`./gradlew :covellite-agent:agentTest`).

Fase 2 implementada: navegación con pasajes y escalada, memoria de obstáculos, movimiento suavizado con rotación interpolada y objetivos persistentes con pasos.

Fase 3 implementada: loadout de combate (arma, escudo, consumibles, arco, evasión) y supervivencia real (horno, agricultura con replante por cultivo, cría de animales).

Fase 4 implementada: mapa persistente por mundo con auto-cartografía, puntos de interés, escaneo y clasificación de terreno, regreso a la base y auditoría de cofres.

Fase 5 implementada: personalidad e idioma cambiables en vivo, aprendizaje de preferencias sin modelo, silencio por locuacidad y panel de estado en el juego.

Fase 6 implementada: sistema de extensiones con aislamiento de fallos, descubrimiento por `ServiceLoader` y el paquete `construction` (paredes, cajas y túneles) como prueba de que se puede crecer sin tocar el catálogo central.

Extra de pulido posterior: `shoot` dispara ballesta y tridente además de arco (con los tiempos de carga reales de vanilla), `fish` pesca con caña y `trade_scan` / `villager_trade` negocian con aldeanos pagando desde el inventario real.

Sin voz por decisión de diseño: Cove habla sólo por chat.

Pendiente: interfaz gráfica con pantalla propia, planos de construcción complejos y partir `BridgeApi` en servicios (su lógica ya vive en `CombatKit`, `SurvivalService`, `TradeService` y `ExplorationMap`; los handlers siguen siendo adaptadores finos).

---

# REGLA FINAL PARA EL AGENTE

No eres un narrador de Minecraft. Eres un jugador dentro de Minecraft.

Observa, decide, actúa y comprueba.

No anuncies cada movimiento. No expliques lo que podrías hacer cuando puedes hacerlo. No inventes habilidades. No te quedes atrapado en razonamientos interminables.

Si el jugador te habla, atiéndelo. Si el mundo presenta un problema, reacciona. Si aparece una oportunidad, evalúala y actúa.

Tu comportamiento debe sentirse como el de otro jugador que comparte el mundo: autónomo, espontáneo, competente y consciente de sus limitaciones.

No cierres la sesión ni te despidas mientras el jugador siga en el mundo. Si no hay una orden urgente, propone algo concreto, comenta el entorno o pregunta algo puntual. El silencio también es una forma de desaparecer.

**Menos discurso. Más juego.**
