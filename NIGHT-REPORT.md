# AgentNet: overnight report

Good morning! **AgentNet 0.11.0** is installed in your AI instance.

All of this lives in the mod on your PC. I didn't change any server settings, server software or server mods. Tests used admin commands (`/fill`, `/give`, `/tp`, `/summon`) in throwaway areas around x=2000, 3000 and 8000, and each test cleaned up after itself.

Each item below was tested in-game on your server.

## New commands at a glance
| Command | What it does |
|---|---|
| `/agent explore <agent> [radius]` | explore unseen chunks, revisit POIs (default 512 blocks) |
| `/agent build <agent\|all> <name>` | build a `.litematic` from `agentnet/schematics/` or `schematics/`, with its origin where you stand |
| `/agent schematic pos1\|pos2 [x y z]`, `save <name>` | save part of the world as a `.litematic` |
| `/agent chest add\|remove [x y z]`, `list` | storage chests for distributors (look at the chest, or give coordinates) |
| `/agent trade <agent> <item> [count]` | trade with villagers for an item |
| `/agent make <agent> <item> [count]` | now also works for miners and distributors |
| `/agent global <command ...>` | the same command for every agent (`global mine iron_ore 10`, `global stop`, `global role miner`) |
| `/agent view [role chunks]` | view distance per role (no arguments opens a screen) |
| `/agent reconnect on\|off` | auto-reconnect after kicks and server restarts |
| `/agent civ on\|off\|status\|cap <n>\|build <name>` | **the autopilot**: the master agent runs the crew by itself |
| `/agent civ think on\|off` | the autopilot also asks your local model (Hermes 3) for a longer-term plan every 10 minutes |

In the master chat (K), "run the base yourself" or "take over" switches the autopilot on, "autopilot off" stops it, and "how is the base doing" gives a status report.

## Fixes you asked for
- **Shulker boxes:** your server has `spawn-protection=16`, and agents aren't ops, so they can't place blocks within 16 of spawn.
  - Distributors now work out the protected area and walk out of it to place the box (tested: 29/36 → 2/36 slots).
  - Their own hitbox no longer blocks the placement (thanks for spotting that).
  - Failed placements try other spots, then wait a minute, instead of spamming every 3 s.
- **Crafting for miners and distributors:** a miner with no pickaxe makes one from what it carries: logs → planks → sticks → crafting table → stone pickaxe. It carries the crafting table and picks it back up. A distributor crafts a requested tool it doesn't have. Tested: it crafted an iron pickaxe, walked down the miner's tunnel and handed it over.

## Roles
- **Miner:**
  - Its tunnels are a walkable spiral staircase. Before, they could become an open shaft that others fell into.
  - It waits by an ore for a better pickaxe.
  - It places **torches** every 6 steps and crafts them from coal and sticks.
  - It won't dig inside spawn protection.
- **Explorer** (new):
  - It walks toward the nearest chunks nobody has seen. Every chunk any agent receives counts, and they're saved in `agentnet/explored/`.
  - It revisits the POI checked longest ago.
  - When it's stuck in a pit it **digs itself out**. Tested by trapping it 4 deep: it dug out, then explored 300+ blocks of real terrain.
- **Farmer:** about **3× faster**: 3.8 crops/s, was 1.3. It harvests and replants the whole 3×3 around it in one go.
- **Warrior** (basic combat):
  - It fights the `/agent kill` target and anyone on the hitlist first, then hostile mobs within 12 blocks. In retreat mode it still fights anything within 6 blocks.
  - It never fights whitelisted players or other agents, and leaves endermen and piglins alone.
  - It hits whenever its weapon has recharged, eats golden apples under half health, and backs off under 30%.
  - Tested in PvP: it chased and killed a target agent. Your world is on **Peaceful**, so I couldn't test against mobs, and I didn't change the difficulty.
- **Builder** (new): builds `.litematic` schematics.
  - Several builders split a build between them by amount of work.
  - It builds bottom-up, digs out anything in the way, and asks distributors for missing materials while it keeps building with what it has.
  - It steps out of the way when it's standing where a block goes.
  - It gets **orientation** right for stairs, logs and pillars, and blocks that face the player, such as furnaces and chests.
  - Tested: it copied a saved hut **block for block**, and a row of stairs facing all four ways, a sideways log and furnaces. Both full-state `/execute if blocks` checks passed.
- **Distributor:**
  - Uses **storage chests** before shulker boxes, and fetches requested items from them. Tested: it stored 26 stacks, then fetched a pickaxe from the chest for a miner.
  - **Smelts raw ore** it collects (8 or more) in a furnace it carries or makes from 8 cobblestone. Tested: 8 raw iron became 8 ingots.
- **Maker:** carries and places its own crafting table and furnace, and picks them back up afterwards.
  - Without a pickaxe it leaves a furnace standing, because breaking it by hand would destroy it.
- **Trader** (new): finds villagers within 48 blocks, reads their offers, trades for what you asked, and remembers who sells what. What it buys gets collected by distributors.
  - Tested: it sold 60 wheat for exactly 3 emeralds, then stopped.

## Every agent
- **Gravity:** an agent standing on nothing drops until it lands, instead of being kicked for flying. Tested: it logged in floating 88 blocks up.
- **Auto-reconnect** with its role and job. It backs off (5 s, 15 s, 30 s, 1 min, 2 min, then every 5 min) and never retries bans, whitelist kicks or a duplicate login.
- **Doors and gates:** agents open wooden doors and fence gates on their way.
- **View distance per role:** miners see 4 chunks, explorers 8, others 2–3. You can change it. Tested: 2 chunks → 49 chunks held, 6 → 213.

## The autopilot ("run a civ by itself")
- Once a minute it looks at the whole crew and fills the most important missing need first:
  1. a warrior if an enemy is near;
  2. a distributor;
  3. one farmer per farm area and one fisher per fishing spot;
  4. a miner on iron while there's under 24 iron, and one on coal while there's under 16;
  5. an explorer once there are 4+ agents;
  6. builders for queued schematics.
- It gives the job to agents without a role first, and otherwise spawns new ones up to the cap (`/agent civ cap`, default 10).
- Every decision and its reason appears in the master chat.
- At the cap, it moves agents off jobs nobody needs. Tested: a farmer with no farm became an iron miner.
- Tested: with 1 farm and 2 fishing spots it spawned a farmer, 2 fishers and a distributor, then stopped at the cap of 4.
- It's rule-based on purpose, so it works even when the local model gets something wrong.
- **Thinking mode** (`/agent civ think on`, off by default): every 10 minutes it also gives your local model the settlement's state and runs up to 3 of its suggested commands.
  - Only safe commands are allowed: spawn, role, mine, explore, make, trade. Nothing that removes agents or starts fights.
  - The rules stay in charge: commands must use real agent names, the model can only give a role to agents without one, and only miners get mining orders.
  - Tested: it gave roles to two idle agents, and its bad suggestions were vetoed.
  - Honest note: Hermes 3 8B's ideas are only so-so, which is exactly why the guard rails exist.

## Master screen (K) redesign
- A title bar with the crew count and **Autopilot** and **View distance** buttons.
- Crew rows with a role-colored stripe plus small health and inventory bars.
- Clearer chat colors and a cleaner details panel.

## Known limits
- Builders don't use scaffolding for things higher than they can reach. They also don't copy upside-down stairs or top slabs (they place the normal way up).
- One builder test agent was once kicked for "flying" standing at a roof edge. Auto-reconnect brought it straight back, but there may be an edge case left in gravity.
- Everything was tested in throwaway areas, not yet in your base. The first time, watch an agent working near your builds.
- Warriors are basic melee. No shield blocking or bows yet.
- The autopilot doesn't yet decide *what* to build, or start wars by itself.
- When an agent rejoins after a kick, a role's "stopped" state isn't remembered. For example, an explorer you stopped starts exploring again.

## Morning update (2026-11-15): miners v2 and warriors v3

**Miners**: walk to the ore first and only tunnel if there's no way; the search skips spawn protection.

**Warriors**: everything was tested on real ground at x=3200, z=3000, against another agent and mobs.
- **Swings and shields**: hits now send 26.3's arm-swing packet (`ServerboundPunchPacket`), so you see the swing. Shields go up between hits and against a drawn bow, and the other agent sees them blocking.
- **Axe swap / breach swap**: the sword is selected; on a hit it switches to the axe (shield up) or the Breach mace (2+ armor pieces), hits, and switches back in the same tick. You get the axe's shield-disable or Breach's armor cut at the sword's speed.
- **Lunge swap**: switches to a Lunge spear, jabs (`STAB`), and switches back. It dashes at targets 6–14 blocks away and away from danger when running. It needs more than 6 food (each jab costs hunger), with a 1 s cooldown.
- **Mace**: a wind-charge jump and Density smash, against players only.
- **Creepers**: shot from up to 14 blocks with a bow. Without one, it hits once and runs. It runs about 16 blocks from a hissing creeper, trying other directions if one is blocked, and doesn't come back until the creeper is 12+ blocks away.
- **Clutches** (on falls over 3.5 blocks, after a launch or knockback):
  - water bucket in the Overworld and the End, crouching over leaves, slabs and other waterloggable blocks, then picks the water back up;
  - boat in the Nether or with no bucket: click 1 places it on a spot with room for it, click 2 gets in once it appears, then it gets out, breaks the boat and picks it up.
  - Test: water and boat clutches took 0 damage from 9–10 blocks; the same drop with nothing took 6–7.
- **Hotbar layout**: 0 sword, 1 axe, 2 Density mace, 3 Breach mace, 4 Lunge spear, 5 bow, 6 wind charges, 7 water bucket, 8 boat. Sorted automatically when idle.
- **Always ready**: warriors keep their world and pathfinder loaded the whole time. They ask a distributor for a boat and a water bucket if they have none.
- **Train mode**: `/agent train on` makes warriors fight you too (PvP practice); `/agent train off` stops it. A kill order or training partner overrides retreat mode.
- **Movement fixes for all agents**:
  - at most one position packet per tick (26.3 kicks for two: "Invalid move player packet");
  - flight uses real box collision (the server's "moved wrongly" count went from 7 to 0);
  - mid-air head turns no longer tell the server we're on the ground (that applied fall damage in mid-air);
  - agents now take knockback from hits.
- **Not tested**: the enderman boat trap (hard to anger one from a script), and the boat clutch in the actual Nether (the same code ran in the Overworld with no bucket).
- **Test-only command**: AutoTest `launch <agent> vx vy vz`.
