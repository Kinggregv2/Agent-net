# Agents vs VexBot

One AgentNet warrior against one VexBot (VexBot 3.2.0, default difficulty), the same kit on both, in a walled
arena with no roof. A round is 120 s; whoever dies loses, otherwise it is a draw. Test world only.

## Scoreboard (2026-11-25)

| Mode | Kit | Agent - VexBot |
|---|---|---|
| Elytra mace | mace, elytra, rockets, sword, gapples, totem | 3 - 0 |
| Mace | mace, wind charges, pearls, sword, gapples, totem (no fireworks) | 3 - 0 |
| Sword, no food | sword, netherite armor | 3 - 0 |
| Trident mace | Riptide trident, mace, wind charges, rain | 2 - 0 (1 draw) |
| Crystal | crystals, obsidian, totems, Blast Protection | 2 - 0 |
| Breach swap | sword and a Breach mace | 2 - 0 |
| Cart | rails, TNT carts, crossbow, Flame bow | 2 - 0 (its carts still fail often) |
| Spear mace | Lunge spear, mace, wind charges, pearls | 2 - 1 |
| Trident elytra | Riptide trident, elytra, mace, rain | 1 - 0 (2 draws) |
| Sword, 32 steak each | sword, armor, steak | draws (agent ahead on damage; both heal) |

## What made the difference

- **Pearl catch**: a pearl straight up, a wind charge after it; the pearl hits the charge and takes its thrower
  about 22 blocks up, then the mace. The charge is timed from the pearl's spawn packet (the server handles the
  throw a few ticks late) and aimed where the pearl will be.
- **Breach swap**: the Breach mace swapped in for one hit and straight back, in the same tick.
- **Riptide**: the throw's push is done by the thrower's own client in vanilla, so the agent pushes itself.
- **Crit chaining**, eating in the gaps, sprinting to chase, a dodge (walking and a wind charge) from mace dives.
- In 26.3 any item use that swings the arm resets the attack charge, and a pearl teleport resets the fall.
- Test setting: `/agent leaveondeath on|off`.
