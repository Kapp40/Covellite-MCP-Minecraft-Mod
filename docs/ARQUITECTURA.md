# CoveMCP — Arquitectura y estado del proyecto

Documento técnico del mod y de la IA que vive dentro de él. Resume cómo está
construido, qué garantiza hoy y qué falta. Última actualización: 15/11/2026.

- Minecraft **1.21.1**, NeoForge **21.1.x**, Java **21**.
- Mod id `covemcp`, versión visible `BetaReview4`.
- JAR actual: **~527 KB**, sin dependencias nuevas ni descargas.
- 105 archivos Java de código fuente; ~8.700 líneas en el agente, ~13.700 en el mod.

---

## 1. Idea central

Cove no es un cliente MCP que se conecta a una IA: es un **jugador compañero**
(entidad server-side, `AIPlayer65`) con **una IA embebida en el propio JAR**.
La IA tiene dos facultades separadas:

1. **Cuerpo**: moverse, minar, talar, pelear, comer, dormir, construir, usar cofres.
2. **Mente**: entender lo que le dicen, responder, recordar, decidir cuándo hablar
   o actuar por su cuenta.

La mente no usa red ni modelos externos por defecto. El "lenguaje" es un motor
propio en Java: léxico, morfología española, parser de actos de habla y realizador
de frases. Los proveedores HTTP (Ollama, LM Studio, OpenAI, Anthropic, Gemini)
siguen existiendo como opción, pero no son necesarios.

---

## 2. Módulos

| Módulo | Rol |
| --- | --- |
| `covellite-core` | Utilidades puras: JSON, colas, relojes, utilidades sin Minecraft. |
| `covellite-agent` | Runtime de IA (Java puro, testeable sin juego): loop, memoria, metas, proveedores, mente social y motor de lenguaje. |
| `covellite-bridge` | Contrato del puente (protocolo, transporte, cliente/servidor MCP). |
| `covellite-mod` | Mod NeoForge: entidad compañero, tareas, pathfinding, registro de tools, comandos, chat y autopilot. |

Los fuentes de `core`, `bridge` y `agent` se compilan **dentro** del JAR del mod
(`srcDir`), más los recursos del agente (`cove/lexicon.txt`), para que todo viaje en
un único artefacto.

---

## 3. Capas en ejecución

### 3.1 Cuerpo (hilo del servidor)

- `CompanionManager` / `CompanionPlayer`: entidad tipo jugador, playerdata, spawn,
  respawn, inventario, manos, health/food.
- `Controller`: entradas reales (adelante, strafe, salto, sprint, sneak, ataque).
- `Pathfinder`: A* por bloques con escalada, pasajes y replanificación.
- **Tareas con prioridad** (`Task`): `FollowTask` 55, `GatherTask` 60, `GotoTask`,
  `SleepTask`, `FishingTask`, `CraftTask`, `GuardTask`, reflejos 91-100.
  `startTask` compara prioridad: lo urgente preempta, lo demás se encola (máx. 8).
- `autopilotTick` corre cada tick: reflejos, combate, guardia, atasco, durabilidad,
  inventario, auto-comida, reanudación de seguimiento y **reflejo cuidador de tareas**.

### 3.2 Puente y tools (hilo del servidor)

- `BridgeApi`: dispatcher con **59 tools** (`core` + `construction`), registro de
  acciones, historial de chat, permisos por cofre, eventos y estados.
- Servidor HTTP local en `127.0.0.1:17452` para clientes MCP externos.
- `get_action_status`, `cancel_action`, `emergency_stop` para observabilidad.
- Las tools que tocan el mundo se ejecutan **siempre dentro del tick del servidor**;
  el agente solo encola y espera resultado.

### 3.3 Runtime del agente (hilo `CoveMCP-agent`)

- `AgentRuntime`: orquesta chat, turnos, memoria, objetivos y ciclo social.
- `AgentLoop`: hasta 8 pasos por turno, deadline de 45 s, tope de errores
  consecutivos de tool, cancelación cooperativa.
- `AgentMemory`: conversación, preferencias, errores, datos semánticos; recuperación
  por palabras clave y ventana reciente.
- `AgentGoals`: objetivos persistentes con pasos.
- Proveedores: `cove` (embebido), `ollama`, `lmstudio`, `llamacpp`, `openai`,
  `anthropic`, `gemini`, `openai-compatible`.
- `BrainArbiter`: **un solo cerebro manda**. `/cove brain local|mcp` cambia de forma
  atómica y pausa el otro.

### 3.4 Mente social (persistente por mundo)

- `SocialState`: pregunta pendiente (texto, tema, motivo, contexto, conversación,
  recordatorios) + intercambios + contadores. Archivo `social/state.json`.
- `Identity`: rasgos (curiosidad, humor, cautela, iniciativa, apego, confianza),
  opiniones, ánimo con vencimiento. Archivo `social/identity.json`.
- `SocialPolicy`: filtro determinista que decide **si vale la pena pensar**
  (cooldowns, tope de iniciativas, espera de respuesta, motivos concretos).
- `SocialInterpreter`: lectura del mensaje (en modo embebido la hace el parser).
- `Mind`: deliberación → silencio, hablar o iniciativa, con motivo y ánimo.

### 3.5 Motor de lenguaje propio

| Clase | Función |
| --- | --- |
| `Lexicon` | Base de conocimiento en texto: items, lugares, criaturas, direcciones, sentimientos, verbos, frases y tono. Indexa **todas las conjugaciones** de cada verbo y de los infinitivos de sus sinónimos. |
| `Spanish` | Normalización, tokenización, plurales y conjugación completa: presente, pretérito, imperfecto, futuro, condicional, subjuntivo, imperativo voseo, gerundio, participio. |
| `Parser` | Comprensión: acto de habla, entidades, negación, segunda persona, homónimos, frases hechas y respuesta a la pregunta pendiente. Detecta incongruencia (broma). |
| `Realizer` | Generación: planes de discurso con ranuras, rotación y filtro de novedad. Nunca imprime ranuras vacías. |
| `OfflineBrain` | Decisión (silencio/habla/iniciativa) y planificación de tools con argumentos. |
| `CoveProvider` | Adaptador: implementa `AgentProvider` y responde los tres prompts del runtime sin red. |

Base actual: **252 líneas / ~10 KB**, ~40 frases de jerga, más de **2.500 formas
verbales** indexadas. Todo editable; hay un archivo extra por partida
(`covemcp/lexicon-extra.txt`) para enseñar palabras sin recompilar.

---

## 4. Flujo de un mensaje

```
jugador escribe
  → CovelliteAI.onServerChat
  → AgentBridge.onHumanChat (solo si la autoridad es local)
  → AgentRuntime.handleHumanChat (hilo del agente)
      1. Parser: acto + entidades + negación + respuesta a pregunta pendiente
      2. SocialState: resuelve o conserva la pregunta
      3. AgentLoop: prompt con memoria, identidad, estado social y pendiente
           - orden   → ToolCall existente (con cantidad/radio/material)
           - negada  → acuse sin ejecutar
           - saludo  → saludo por nombre
           - resto   → Realizer según acto
      4. afterTurn: guarda intercambio, actualiza identidad, persiste
  → chat del juego
```

Actos reconocidos: orden, pregunta al mundo, pregunta sobre Cove, respuesta
sí/no/dato/vaga, broma, corrección, saludo, preferencia, sentimiento, elogio,
insulto, plan, charla, duda ("no te entendí").

---

## 5. Ciclo autónomo

Cada **20 ticks (1 s)** se actualizan señales: jugadores y distancia, vida, comida,
noche, distancia al spawn, separación, bioma, actividad.

`SocialPolicy` decide si pensar. Motivos, en orden: tarea terminada, herida
(vida < 8), hambre (comida ≤ 6), lejos de casa (> 220 bloques), separado (> 36),
noche, 90 s de silencio, iniciativa > 0.6. Límites: 45 s entre hablas, 40 s entre
deliberaciones, 6 iniciativas por ventana, espera de respuesta 10 min.

`OfflineBrain.decide` traduce eso a: silencio, aviso, pregunta (con tema y motivo
guardados) o iniciativa con tool (`eat`, `return_home`, `follow`). Silencio es la
respuesta normal.

---

## 6. Órdenes que duran

- **`seguime`**: seguimiento persistente. Llega, se detiene y vuelve a caminar si el
  jugador se aleja. Solo `pará` / `quedate` lo cancelan.
- **`vení` / `ven acá` / `acercate`**: `follow` con `once=true`; termina al llegar.
- **`mine` / `gather`**: piden `count=32`, `radius=32`, `timeout_ticks=6000` y el
  material correcto (hierro, piedra, tierra, arena, esmeralda, redstone, madera…).
- **`tala` / `rompe el árbol`**: `max_logs=64`, radio 32.
- **Reflejo cuidador**: si un reflejo de supervivencia interrumpe una recolección, se
  reanuda hasta 3 veces, 80 ticks después del reflejo. Una orden explícita la cancela.
- Si no hay material cerca, la tool falla y Cove lo dice; no finge éxito.

---

## 7. Persistencia

Todo bajo `minecraft/covemcp/`:

| Ruta | Contenido |
| --- | --- |
| `agent/<mundo>/social/state.json` | Pregunta pendiente, intercambios, contadores. |
| `agent/<mundo>/social/identity.json` | Rasgos, opiniones, ánimo. |
| `agent/<mundo>/conversation.jsonl`, memoria | Historial y memoria del runtime. |
| `lexicon-extra.txt` | Vocabulario editable por el jugador. |

Cerrar Minecraft no borra nada.

---

## 8. Configuración relevante (`config/covemcp.properties`)

```
agentEnabled=true          # mente local encendida
agentProvider=cove         # cerebro embebido; sin red
brain=local                # local (embebido) | mcp (invitado externo)
agentSocialEnabled=true
agentSocialMinGapMs=45000
agentSocialThinkMs=40000
agentSocialMaxInitiations=6
agentSocialQuestionTtlMs=600000
agentSocialInitiative=true
playerName=AIPlayer65
port=17452
```

Comandos útiles: `/cove mind`, `/cove why`, `/cove ai status`, `/cove ai ask`,
`/cove panel`, `/cove brain local|mcp`, `/cove on|off`.

---

## 9. Verificación actual

- `agentTest`: **189/189** checks (léxico, conjugación, actos, jerga, bromas,
  planificación de tools, decisión, proveedor embebido).
- `:covellite-mod:build` correcto (con `-Xmx4g`; el compilador de NeoForge necesita
  heap en esta máquina).
- JAR instalado en la instancia, SHA-1 verificado contra el compilado.
- Commit actual: `5146448` en `master`.

**Importante:** la verificación es unitaria. El comportamiento en partida
(pathfinding, tiempos, tacto conversacional) **todavía no está validado** de punta a
punta. Los bugs reportados hasta ahora se encontraron jugando, no en tests.

---

## 10. Qué falta

### Corto plazo (alto impacto)

1. **Verificación en partida** de cada orden: `vení`, `seguime`, `mine`, `gather`,
   `tala`, `pará`, y de las iniciativas (pregunta espontánea, comer, volver).
2. **Aprendizaje en caliente de palabras**: hoy se enseña editando
   `lexicon-extra.txt` y reiniciando. Falta que Cove pregunte "¿qué es X?" y guarde
   la respuesta en ese archivo durante la partida, con recarga del léxico.
3. **Minería real cuando no hay mena cerca**: rutina de rampa/túnel (bajar 20
   bloques, abrir galería, volver a buscar) con sus tests de navegación.
4. **Umbral de llegada configurable** para el `vení` one-shot si en la práctica se
   queda a 4 bloques sin completar.

### Medio plazo

5. **Memoria semántica de verdad**: hoy la recuperación es por palabras clave;
   falta indexar entidades y relaciones (lugares, construcciones, favores, deudas).
6. **Diálogo con seguimiento de tema** en varias réplicas (hoy cada turno se
   interpreta aislado, salvo la pregunta pendiente y la ventana de intercambios).
7. **Comprensión de ironía sin contradicción explícita** (la actual detecta
   incongruencia y risa, no sarcasmo fino).
8. **Iniciativa cuando el cerebro es MCP**: hoy el ciclo autónomo depende del cerebro
   local; con `brain=mcp` no hay evento que despierte al cliente externo.
9. **Más jerga y regionalismos**: el archivo es la vía, pero falta una tanda grande
   por región y un test de cobertura.

### Largo plazo

10. **Visión/entorno estructurado**: describir lo que ve (bloques alrededor, cofres,
    cultivos) para preguntas del tipo "¿qué hay acá?".
11. **Multiplicidad de jugadores**: prioridades sociales, celos de atención, no
    repetir la misma respuesta a dos jugadores.
12. **Internacionalización**: el realizador está en español rioplatense fijo.
13. **Audio/TTS**: se quitó a propósito. Solo volvería como módulo opcional.
14. **Perfilado de rendimiento** en sesiones largas (memoria, tamaño de archivos,
    coste del tick con muchas tasks).

---

## 11. Límites conocidos (honestos)

- El vocabulario es cerrado al léxico. Una palabra fuera del archivo no se entiende
  (se trata como desconocida y Cove pregunta).
- La generación recombina planes con ranuras: coherente y variable, no discurso
  libre como un modelo grande.
- La comprensión es morfológica y estructural, no semántica profunda.
- Sin red no hay "conocimiento del mundo": solo lo que está en la base y en la
  memoria del runtime.
- El cuerpo depende del pathfinding del mod: mundos rotos, paredes o acantilados
  pueden hacer fallar una tarea, y ahora al menos lo reporta.
