# CovelliteAI

Autonomous AI-controlled Minecraft companion for NeoForge 1.21.1.

The mod runs in its **own separate Minecraft client instance** and drives only
that instance's `LocalPlayer`. It is not a FakePlayer, an NPC, or an
automation of the human player's client.

## Stack

- Minecraft 1.21.1
- NeoForge 21.1.80
- Java 21
- Gradle 8.10.2

## Status

| Component | Status |
|-----------|--------|
| Java 21 toolchain | `IMPLEMENTED / TESTED` |
| Gradle wrapper 8.10.2 | `IMPLEMENTED / TESTED` |
| `covellite-core` JSON / frame codecs | `IMPLEMENTED / COMPILED / TESTED` |
| `covellite-core` HMAC handshake | `IMPLEMENTED / COMPILED / TESTED` |
| `covellite-core` action queue, event log, state diff, dispatcher | `IMPLEMENTED / COMPILED / TESTED` |
| `covellite-core` TCP server/client | `IMPLEMENTED / COMPILED / TESTED` |
| `covellite-mod` NeoForge integration | `IMPLEMENTED / COMPILED / TESTED` (loads in dev client; bridge listens) |
| `covellite-mod` movement/chat/perception | `IMPLEMENTED / COMPILED` (not yet exercised in a live world) |
| `covellite-bridge` MCP STDIO bridge | `IMPLEMENTED / COMPILED / TESTED` (27 tools) |
| Bridge end-to-end (MCP → TCP → mod) | `TESTED` (fake mod + real mod `get_status`) |
| Two independent clients in one world | `PENDING` (requires a second instance/server) |
| Skin asset | `PROVIDED` (`assets/covelliteai_skin.png`, 64×64 PNG) |

Evidence for the current state:

- `:covellite-core:selfTest` → `passed=8 failed=0`.
- `:covellite-bridge:bridgeTest` → authentication, 40 concurrent correlated
  calls, timeout/error mapping, and a real MCP STDIO process calling tools.
- `:covellite-mod:runClient` → mod loads, logs
  `CovelliteAI bridge listening on 127.0.0.1:17452`.
- Live check against that client: MCP `initialize` and `get_status` returned
  `{"mod":"covelliteai","bridge_authenticated":true,...}`.

## Layout

```
covellite-core     pure Java library: JSON, framing, HMAC, actions, dispatch
covellite-mod      NeoForge client mod: LocalPlayer adapter + bridge server
covellite-bridge   MCP server over STDIO; forwards tool calls over localhost TCP
assets             player skin
```

The mod compiles `covellite-core` sources into its own output so the
distributed jar is self-contained and the NeoForge dev classloader can see the
core classes.

## Setup

PowerShell:

```powershell
$env:JAVA_HOME = "C:\Users\Lorex\.qoder\jdks\jdk-21.0.13+9"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
```

Bash:

```bash
export JAVA_HOME="/c/Users/Lorex/.qoder/jdks/jdk-21.0.13+9"
export PATH="$JAVA_HOME/bin:$PATH"
```

## Build and test

```bash
# Core unit tests
./gradlew :covellite-core:selfTest

# Compile the mod
./gradlew :covellite-mod:compileJava

# Self-contained mod jar
./gradlew :covellite-mod:jar

# Bridge distribution + end-to-end test
./gradlew :covellite-bridge:installDist :covellite-bridge:bridgeTest

# Everything at once
./gradlew :covellite-core:selfTest :covellite-mod:jar \
          :covellite-bridge:installDist :covellite-bridge:bridgeTest
```

## Running

### 1. Start the AI's Minecraft client

```bash
./gradlew :covellite-mod:runClient
```

The mod starts a localhost-only TCP server on port `17452`. On first start it
creates the shared secret at:

```
~/.covelliteai/covelliteai_token.txt
```

Configuration lives at `config/covelliteai.properties` (relative to the game
directory):

```properties
playerName=CovelliteAI
port=17452
autoConnect=false
serverAddress=
maxActions=32
maxActionHistory=256
stateUpdateTicks=5
maxChatHistory=256
```

Set `autoConnect=true` and `serverAddress=host:port` to join a server
automatically from the title screen.

### 2. Start the MCP bridge

The bridge speaks MCP over STDIO and must be launched by the MCP client:

```bash
./covellite-bridge/build/install/covellite-bridge/bin/covellite-bridge \
  --port 17452 \
  --token-file ~/.covelliteai/covelliteai_token.txt
```

On Windows use `covellite-bridge.bat`. stdout is reserved for MCP JSON-RPC;
diagnostics go to stderr. The bridge keeps retrying the TCP connection while
Minecraft is still loading, so tool calls return `mod_not_connected` until the
mod is authenticated.

### 3. Connect an MCP client

Use the command above as a STDIO MCP server. The bridge exposes 27 tools:
status/perception (`get_status`, `get_player_state`, `get_world_info`,
`get_player_list`, `get_inventory`, `get_equipment`, `get_hotbar`,
`get_nearby_entities`, `get_nearby_blocks`, `get_targeted_object`,
`get_entity_info`, `get_block_info`, `get_chat_history`,
`get_recent_events`) and control (`move`, `look`, `look_at`, `jump`,
`set_sneak`, `stop`, `follow`, `goto`, `get_action_status`, `cancel_action`,
`emergency_stop`, `reset_emergency_stop`, `send_chat`).

## Security and threading

- TCP binds to `127.0.0.1` only.
- HMAC-SHA256 challenge-response; the token never crosses the wire.
- Bounded queues, request timeouts, cancellation and an emergency-stop latch.
- Network threads only enqueue; the client thread drains requests in
  `ClientTickEvent.Pre` and pumps the action queue once per tick.
- The mod only reads and controls `Minecraft.player` of its own instance.

## Skin

`assets/covelliteai_skin.png` is a 64×64 PNG. The mod does not force a skin on
an account: apply it to the AI account/profile through the normal launcher or
server skin mechanism.

## Limitations

- The two-client live test in a real world has not been performed yet; the mod
  has been verified up to the title screen with a live MCP call.
- Movement, look, follow and goto are implemented but only unit/compile
  verified so far.
- No block breaking, placing, crafting, combat or persistent memory yet
  (Phase 2).
- `get_screenshot` is intentionally not exposed because it is not implemented.
