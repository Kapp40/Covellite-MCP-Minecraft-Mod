# CovelliteAI

Un jugador artificial que vive **dentro de tu propio mundo de un jugador** y que
cualquier modelo de IA con soporte de *tool calling* / MCP puede controlar:
moverse, mirar, saltar, agacharse, caminar a un punto, seguirte y hablar por
chat.

No hay segundo cliente, ni segunda cuenta, ni servidor aparte, ni simulación de
teclado/ratón.

```
┌──────────────────────────┐        MCP (STDIO)        ┌───────────────────────┐
│  Modelo de IA / agente   │ ◄───────────────────────► │  covellite-bridge     │
│  (Claude, GPT, Gemini,   │   tools/call, tools/list  │  (proceso Java)       │
│   DeepSeek, local, ...)  │                           └──────────┬────────────┘
└──────────────────────────┘                                      │ TCP 127.0.0.1:17452
                                                                  │ HMAC-SHA256
                                                       ┌──────────▼────────────┐
                                                       │  covellite-mod        │
                                                       │  dentro de Minecraft  │
                                                       │  (servidor integrado) │
                                                       └───────────────────────┘
```

## Qué es y qué no es

| Sí | No |
|---|---|
| Un `ServerPlayer` real registrado en tu mundo singleplayer | Un segundo cliente de Minecraft |
| Controlado por el modelo a través de herramientas MCP | Un bot que mueve tu teclado o tu ratón |
| Físicas, colisiones, vida, hambre e inventario normales | Un `FakePlayer` de granja |
| Aislado a tu `IntegratedServer` local | Algo que se conecta a servidores dedicados |

El mod comprueba explícitamente que el servidor sea
`net.minecraft.client.server.IntegratedServer` **y** `server.isSingleplayer()`.
Si esa condición no se cumple, no aparece ningún jugador artificial.

## Requisitos

- Minecraft **1.21.1** con **NeoForge 21.1.x**
- **Java 21** (el mod y el puente)
- Un launcher que cargue mods (Prism, CurseForge, MultiMC, etc.)
- Un **modelo/agente con soporte de MCP** o de *function calling* (Claude, GPT,
  Gemini, DeepSeek, Qwen, Llama local con un agente MCP, OpenCode, Cursor,
  Claude Desktop, etc.)

### Recomendación fuerte

Usa una **copia desechable del mundo**. El compañero puede caerse, romper algo
sin querer o quedarse atascado, y no queremos eso en tu partida principal.

## Instalación

### 1. Compila el mod y el puente

```bash
# Linux / macOS
export JAVA_HOME=/ruta/a/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :covellite-mod:build :covellite-bridge:installDist

# Windows (PowerShell)
$env:JAVA_HOME = "C:\ruta\a\jdk-21"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :covellite-mod:build :covellite-bridge:installDist
```

Salidas:

```
covellite-mod/build/libs/covellite-mod-0.1.0-SNAPSHOT.jar
covellite-bridge/build/install/covellite-bridge/bin/covellite-bridge      (Linux/macOS)
covellite-bridge/build/install/covellite-bridge/bin/covellite-bridge.bat  (Windows)
```

### 2. Instala el mod

Copia el jar a la carpeta `mods/` de la instancia:

```
<instancia>/minecraft/mods/covellite-mod-0.1.0-SNAPSHOT.jar
```

### 3. Arranca Minecraft una vez

Dentro del juego se genera el token compartido:

```
~/.covelliteai/covelliteai_token.txt      (%USERPROFILE%\.covelliteai\... en Windows)
```

Ese archivo es el secreto del handshake HMAC. **No lo compartas.**

### 4. Carga un mundo de un jugador

El mod crea al compañero automáticamente al iniciar el servidor integrado y lo
anuncia en el chat. El puente TCP ya escucha desde el arranque del juego, pero
las herramientas solo responden **cuando hay un mundo cargado y el servidor
integrado está corriendo** (el despachador se ejecuta en el tick del servidor).

## Conectar cualquier modelo de IA

El puente habla MCP sobre **STDIO**: es el cliente MCP (el que está dentro de tu
agente) quien lanza el proceso. Cada cliente tiene su propio formato de
configuración; estos son los dos más comunes.

### Formato genérico (`mcpServers`)

Claude Desktop, Cursor y muchos otros:

```json
{
  "mcpServers": {
    "covellite": {
      "command": "C:\\ruta\\covellite-bridge\\bin\\covellite-bridge.bat",
      "args": [],
      "env": { "JAVA_HOME": "C:\\ruta\\a\\jdk-21" }
    }
  }
}
```

### Formato OpenCode (V2)

`%USERPROFILE%\.config\opencode\opencode.jsonc` en Windows,
`~/.config/opencode/opencode.jsonc` en Linux/macOS:

```jsonc
{
  "mcp": {
    "servers": {
      "covellite": {
        "type": "local",
        "command": [
          "cmd.exe", "/c",
          "C:\\ruta\\covellite-bridge\\bin\\covellite-bridge.bat"
        ],
        "environment": { "JAVA_HOME": "C:\\ruta\\a\\jdk-21" }
      }
    }
  }
}
```

> Si tu versión de OpenCode rechaza `mcp.servers` y exige los servidores
> directamente bajo `mcp`, usa `"mcp": { "covellite": { ... } }`.

### Opciones del puente

```bash
covellite-bridge --token-file <ruta> --port 17452 --help
```

| Opción | Por defecto | Descripción |
|---|---|---|
| `--token-file` | `~/.covelliteai/covelliteai_token.txt` | Token HMAC compartido con el mod |
| `--port` | `17452` | Puerto TCP local del mod |
| `--help` | — | Ayuda |

La propiedad de sistema `-Dcovelliteai.port=` hace lo mismo que `--port`.

### Si el modelo no habla MCP

Cualquier agente con *function calling* puede usar herramientas MCP mediante un
adaptador. Lo único que necesita el modelo es ver la lista de herramientas y
poder emitir llamadas con argumentos JSON. Con un agente propio, expón las 30
herramientas de la sección siguiente como funciones y reenvía cada llamada al
puente.

## Cómo se juega

Un buen prompt de arranque para el modelo:

```
Eres CovelliteAI, un jugador de Minecraft dentro de mi partida.
Empieza con get_status y get_player_state. Después explora con
get_nearby_blocks y get_nearby_entities, muévete con goto o follow,
y usa look_at antes de caminar. Si algo se atasca, usa stop.
Si te mueres, respawneas solo a los 3 segundos.
No toques bloques importantes de mi base.
```

Flujo típico:

```
get_status                         → ¿está conectado?, ¿hay compañero?
get_player_state                   → posición, vida, hambre
get_nearby_entities {radius:16}    → ¿quién hay cerca?
get_nearby_blocks {radius:8, blocks:["ore"]}
goto {x,y,z,tolerance:1}           → action_id
get_action_status {id}             → running / completed / failed + motivo
follow {player:"TuNombre", distance:3}
stop                               → cancela todo el movimiento
```

## Herramientas MCP (30)

### Percepción

| Herramienta | Parámetros | Devuelve |
|---|---|---|
| `get_status` | — | Estado del mod, del puente y del compañero |
| `get_player_state` | — | Posición, rotación, vida, comida, aire, modo de juego |
| `get_world_info` | — | Dimensión, hora, dificultad, clima, bioma |
| `get_player_list` | — | Jugadores en línea con posición y modo |
| `get_inventory` | — | Stacks no vacíos con slot, item, cantidad y daño |
| `get_hotbar` | — | Las 9 casillas de la hotbar y la seleccionada |
| `get_equipment` | — | Mano principal, secundaria y armadura |
| `get_nearby_entities` | `radius` (1-64, 16), `limit` (1-128, 32) | Entidades ordenadas por distancia |
| `get_nearby_blocks` | `radius` (1-16, 8), `blocks` (substrings), `limit` (1-256, 64) | Bloques no-aire cercanos |
| `get_block_info` | `x`, `y`, `z` | Bloque, dureza, sólido, fluido, block entity |
| `get_entity_info` | `entity` (UUID o nombre) | Detalles de una entidad cercana |
| `get_targeted_object` | — | Lo que apunta la mirada (bloque o entidad) |
| `get_chat_history` | `limit` | ⚠️ *Stub*: devuelve lista vacía |
| `get_recent_events` | `since`, `limit` | ⚠️ *Stub*: devuelve lista vacía |

### Control

| Herramienta | Parámetros | Comportamiento |
|---|---|---|
| `move` | `forward` (−1..1), `strafe` (−1..1), `sprint`, `ticks` (1-200) | Escribe la misma entrada que un teclado; devuelve `action_id`. Al terminar incluye `travelled` (bloques) |
| `look` | `yaw`, `pitch` (−90..90) | Rotación instantánea |
| `look_at` | `x`, `y`, `z` | Calcula yaw/pitch hacia un punto |
| `jump` | — | Salto vanilla (funciona parado) |
| `set_sneak` | `enabled` | Agachado persistente |
| `stop` | — | Cancela acciones y movimiento (no activa el latch) |
| `goto` | `x`, `y`, `z`, `tolerance` (0.5-8, 1) | Avance recto; salta si no progresa; falla con motivo tras ~6 s sin progreso |
| `follow` | `player`, `distance` (1-16, 3) | Persigue a un jugador hasta `stop`/`cancel_action` |
| `get_action_status` | `id` | `running`, `completed`, `cancelled`, `failed` + `reason` |
| `cancel_action` | `id` | Cancela esa acción |
| `emergency_stop` | — | Cancela todo y bloquea el control hasta `reset_emergency_stop` |
| `reset_emergency_stop` | — | Quita el bloqueo |
| `send_chat` | `message` | Chat normal, o comando si empieza por `/` (según permisos) |

### Ciclo de vida

| Herramienta | Parámetros | Comportamiento |
|---|---|---|
| `spawn` | — | Crea al compañero si no está |
| `despawn` | — | Lo quita (guarda playerdata) y cancela el respawn automático |
| `respawn` | — | Lo revive al instante |

Todas las acciones devuelven un `action_id` (formato `act-N`) para poder
consultarlas.

## Seguridad y arquitectura

- El TCP del mod escucha **solo** en `127.0.0.1:17452`.
- Handshake **HMAC-SHA256** con reto/respuesta: el token nunca viaja por la red.
- Un solo cliente de puente a la vez.
- Colas acotadas, timeouts por petición, cancelación y *emergency stop*.
- Las herramientas se ejecutan **en el hilo del servidor** (dentro del tick), así
  que tocan el mundo de forma segura.
- El mod nunca crea jugadores fuera de tu `IntegratedServer` singleplayer.
- Sin acceso a red externa por parte del mod.

## Configuración del mod

`config/covelliteai.properties` dentro de la instancia:

```properties
playerName=CovelliteAI
port=17452
maxActions=32
maxActionHistory=256
stateUpdateTicks=5
maxChatHistory=256
```

| Clave | Efecto |
|---|---|
| `playerName` | Nombre del jugador artificial |
| `port` | Puerto TCP local (debe coincidir con `--port` del puente) |
| `maxActions` / `maxActionHistory` | Límites de la cola de acciones |
| `stateUpdateTicks` | Reservado |
| `maxChatHistory` | Reservado |

`autoConnect` y `serverAddress` existen en el archivo por compatibilidad y **no
se usan** en la arquitectura actual (no hay segundo cliente que conectar).

## Solución de problemas

| Síntoma | Causa / solución |
|---|---|
| `mod_not_connected` | Minecraft cerrado o el mundo aún no cargado. Arranca el juego y entra al mundo |
| `timeout: No response from mod` | El servidor integrado está **en pausa** (menú de pausa), el mundo no está cargado o el juego va muy lento |
| Las herramientas desaparecen del agente | Reiniciaste Minecraft. Reconecta el servidor MCP (en OpenCode: `/mcps`) |
| El compañero no aparece | Mira los logs: si el servidor no es `IntegratedServer` singleplayer, el mod no crea jugador |
| El compañero murió | Revive solo a los 3 s; si no, usa la herramienta `respawn` |
| No responde en el menú principal | Normal: sin mundo cargado no hay tick de servidor que despache peticiones |
| Va a tirones | El problema suele ser del pack de mods/cliente, no del puente; sube timeouts en tu agente si hace falta |

### Jugar mientras el humano no mira

En un solo jugador, abrir el menú de pausa **pausa el servidor integrado** y las
herramientas dejan de responder. Para que el compañero siga viviendo mientras
estás en pausa, abre el mundo a LAN (o mantén la ventana activa).

## Estado real / limitaciones

Verificado en partida real (MC 1.21.1, NeoForge 21.1.265):

- Aparición en el mundo, 2 jugadores en la lista, sin duplicados.
- `look`, `look_at`, `jump`, `move` (4 bloques), `goto` (3.9 bloques),
  `follow` (~23 bloques), `set_sneak`, `stop`, `emergency_stop` y `send_chat`.
- Muerte real y **respawn automático en 3 s** con 20/20 de vida.
- Reconexión automática tras reiniciar Minecraft.

Limitaciones conocidas (fase 2):

- Sin romper/colocar bloques, crafteo, ataque, uso de ítems ni contenedores.
- Navegación recta con salto; no hay pathfinding con esquivas de lava/agua.
- `get_chat_history` y `get_recent_events` devuelven listas vacías.
- `latency` de jugadores se reporta como `-1` (1.21.1 no lo expone en servidor).
- Sin memoria persistente: el estado se reconstruye con las herramientas de
  percepción.

## Desarrollo

```bash
./gradlew :covellite-core:selfTest          # tests del núcleo
./gradlew :covellite-mod:build              # jar del mod
./gradlew :covellite-bridge:installDist     # distribución del puente
./gradlew :covellite-bridge:bridgeTest      # test end-to-end alternativo
```

Layout:

```
covellite-core     JSON, framing, HMAC, acciones, dispatcher (Java puro)
covellite-mod      mod NeoForge: CompanionPlayer + puente TCP
covellite-bridge   servidor MCP STDIO que reenvía al mod por localhost
assets             skin 64×64 opcional
```

La skin (`assets/covelliteai_skin.png`) no se aplica sola: ponla en la cuenta o
perfil que uses para el jugador artificial.
