import { Plugin } from "@opencode/plugin"
import { existsSync, readFileSync } from "node:fs"
import { homedir } from "node:os"
import { join } from "node:path"

/**
 * CoveMCP ChatRelay -> session.prompt.
 *
 * The CoveMCP bridge appends every human Minecraft chat line to
 * `~/.covemcp/relay.jsonl` (written by the mod the instant the player presses
 * Enter). This plugin tails that file and admits each new line as a user
 * prompt, so the agent gets a new turn without waiting for the user to type in
 * OpenCode.
 *
 * It is the *best-effort* half of the design: `get_relayed_chat` on the bridge
 * is the guaranteed half and keeps working even when this plugin is disabled,
 * OpenCode is closed, or the client does not run plugins.
 *
 * Options (opencode.jsonc):
 *   { "package": "~/.config/opencode/plugins/covemcp-relay",
 *     "options": { "delivery": "queue", "companion": "AIPlayer65", "pollMs": 1000 } }
 *
 * - sessionID: always send to this session. Omit to use the newest root session.
 * - delivery: "queue" (default) waits for the session to be idle; "steer"
 *   injects into the current run.
 * - companion: name of the AI player; its own lines are ignored defensively.
 * - pollMs: file poll interval (default 1000, minimum 250).
 */

type RelayEntry = {
  seq: number
  from: string
  message: string
  at: number
}

const RELAY_PATH = join(homedir(), ".covemcp", "relay.jsonl")
const LAST_SEQ_KEY = "covemcp-relay/lastSeq"
const SESSION_KEY = "covemcp-relay/sessionID"
const OWNER = Symbol.for("covemcp.relay.owner")

function readRelay(): RelayEntry[] {
  if (!existsSync(RELAY_PATH)) return []
  let text: string
  try {
    text = readFileSync(RELAY_PATH, "utf8")
  } catch {
    return []
  }
  const entries: RelayEntry[] = []
  for (const line of text.split(/\r?\n/)) {
    if (!line.trim()) continue
    try {
      const value = JSON.parse(line) as Partial<RelayEntry>
      if (typeof value.seq !== "number" || typeof value.message !== "string") continue
      entries.push({
        seq: value.seq,
        from: typeof value.from === "string" && value.from ? value.from : "?",
        message: value.message,
        at: typeof value.at === "number" ? value.at : 0,
      })
    } catch {
      // A partially written last line is normal; skip it and try next poll.
    }
  }
  entries.sort((a, b) => a.seq - b.seq)
  return entries
}

function format(entries: RelayEntry[]): string {
  const lines = entries.map((entry) => `${entry.from}: ${entry.message}`)
  return lines.length === 1
    ? `[Minecraft] ${lines[0]}`
    : `[Minecraft] mensajes nuevos:\n${lines.join("\n")}`
}

export default Plugin.define({
  id: "covemcp.relay",
  async setup(ctx) {
    // One poller per server process. The plugin may be instantiated for more
    // than one location; without this guard each instance would send the same
    // line and the agent would receive the message twice.
    const scope = globalThis as Record<symbol, unknown>
    if (scope[OWNER]) {
      return () => {}
    }
    scope[OWNER] = true

    const options = (ctx.options ?? {}) as {
      sessionID?: string
      delivery?: "queue" | "steer"
      companion?: string
      pollMs?: number
    }
    const companion = (options.companion ?? "AIPlayer65").toLowerCase()
    const delivery = options.delivery === "steer" ? "steer" : "queue"
    const pollMs = Math.max(250, Number(options.pollMs) || 1000)

    const stored = await ctx.storage.get(LAST_SEQ_KEY)
    let lastSeq = typeof stored === "number" ? stored : undefined
    if (lastSeq === undefined) {
      // First run: adopt the current tail instead of replaying old chat.
      const entries = readRelay()
      lastSeq = entries.length ? entries[entries.length - 1]!.seq : 0
      await ctx.storage.set(LAST_SEQ_KEY, lastSeq)
    }

    let preferredSession = options.sessionID ?? (await ctx.storage.get(SESSION_KEY))
    if (typeof preferredSession !== "string" || !preferredSession.startsWith("ses")) {
      preferredSession = undefined
    }

    const pickSession = async (): Promise<string> => {
      if (typeof preferredSession === "string") {
        try {
          await ctx.session.get({ sessionID: preferredSession })
          return preferredSession
        } catch {
          preferredSession = undefined
        }
      }
      const page = await ctx.session.list({ limit: 10 })
      const newest = (page.data ?? []).find((session) => !session.parentID)
      if (!newest) {
        throw new Error("no OpenCode session available to receive Minecraft chat")
      }
      preferredSession = newest.id
      await ctx.storage.set(SESSION_KEY, newest.id)
      return newest.id
    }

    let busy = false
    const tick = async () => {
      if (busy) return
      busy = true
      try {
        const entries = readRelay().filter((entry) => entry.seq > lastSeq!)
        if (entries.length === 0) return

        const mine = entries.filter((entry) => entry.from.toLowerCase() !== companion)
        const next = entries[entries.length - 1]!.seq
        if (mine.length === 0) {
          lastSeq = next
          await ctx.storage.set(LAST_SEQ_KEY, lastSeq)
          return
        }

        const sessionID = await pickSession()
        await ctx.session.prompt({
          sessionID,
          text: format(mine),
          delivery,
          metadata: { source: "covemcp-relay" },
        })
        // Advance only after a successful admission (or after deciding to skip
        // companion lines). A failed prompt is not retried automatically: the
        // same text stays available through the get_relayed_chat tool, and
        // retrying could double-post once the session recovers.
        lastSeq = next
        await ctx.storage.set(LAST_SEQ_KEY, lastSeq)
        console.log(`[covemcp-relay] relayed ${mine.length} message(s) to ${sessionID}`)
      } catch (error) {
        console.error("[covemcp-relay] could not relay Minecraft chat:", error)
      } finally {
        busy = false
      }
    }

    const timer = setInterval(() => void tick(), pollMs)
    void tick()

    return () => {
      clearInterval(timer)
      delete scope[OWNER]
    }
  },
})
