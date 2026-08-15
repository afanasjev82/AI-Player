# Brainstorm: Expanding "training" mode beyond combat

> Status: design proposal — not yet implemented. Grounded in the actual
> AI-Player 26.2 source as of 2026-08-15.

## 1. What "training" mode is today (the problem)

`/bot spawn <name> training` sets `modCommandRegistry.isTrainingMode = true` and
runs an **RL (Q-learning) loop** that is *combat/survival-only*:

- It only activates on **hostile entity detection** or **danger-zone proximity**
  (`AutoFaceEntity` → `BotEventHandler.detectAndReact(...)`).
- The action space is the 24 primitive `StateActions.Action` values
  (`MOVE_FORWARD`, `ATTACK`, `SHOOT_ARROW`, `SLEEP`, `HOTBAR_1..9`, …).
- It learns a **Q-table** (`qtable.bin` + `epsilon.bin`) of
  `(state, action) → Q-value`, saved to disk and reused in `play` mode
  (`chooseActionPlayMode` exploits it greedily).

**There is no learning for everyday work.** Exploring, mining, crafting,
building, and farming are handled by an entirely *separate* pipeline
(GoalMapper → HybridPlanner/Markov planner → FunctionCaller → LLM) that is
**not** trained by `training` mode at all. So today, "training" does not make
the bot a better builder or gatherer — it only makes it fight/survive better.

### Inventory of what exists today

| Layer | Count | Contents |
|---|---|---|
| RL actions (`StateActions.Action`) | 24 | movement, turn, sneak/sprint, attack, shoot, equip armor, hotbar 1–9, evade, sleep |
| Function-caller tools (`ToolRegistry`) | 12 | `goTo`, `detectBlocks`, `turn`, `look`, `mineBlock`, `getOxygenLevel`, `getHungerLevel`, `getHealthLevel`, `equipArmor`, `webSearch`, `placeBlock`, `searchBlocks` |
| Goals (`GoalMapper`) | 9 | mine, build, craft, navigate, combat, gather, explore, farm, trade |

### Observed gaps (from live E2E runs)

1. **Hybrid planner can't plan most goals** — `Could not find valid start or
   goal nodes for: craft a wooden sword`, and for `gather`, `build`, `mine`.
   Its action graph nodes have keyword-derived preconditions/effects that rarely
   connect into a path from the bot's real state.
2. **The LLM plan output is unparseable prose** — `qwen3:8b` returns a long
   reasoning block, and `AutonomousGoalEngine` throws
   `MalformedJsonException` when it expects a JSON goal array.
3. **No reward signal for everyday tasks** — nothing measures "the bot gathered
   wood" or "the house got built", so there is nothing to learn from.

## 2. The proposed model: hierarchical skill training

Instead of one flat Q-table over 24 combat primitives, treat everyday work as a
**two-level skill hierarchy**, where "training" mode teaches *skills* and `play`
mode composes them.

```
Level 2  (macro / task)      "build a house", "gather 32 wood", "farm wheat"
             │  composed of
Level 1  (skill)             navigateTo, mineBlock, placeBlock, craft, harvest
             │  executed by
Level 0  (primitive)         the existing 24 StateActions + FunctionCaller tools
```

### 2.1 What the bot should learn (the new skill space)

Each **skill** is a reusable, parameterized procedure with a measurable outcome:

| Skill | Parameters | Success signal (reward) |
|---|---|---|
| `gather` | block type, count | +items in inventory of that type |
| `mine` | block type, count | +blocks broken / items gained |
| `craft` | recipe | +crafted item appears in inventory |
| `build` | structure spec | structure block pattern placed & verified |
| `farm` | crop | +crop planted / harvested |
| `explore` | radius | +newly-visited chunks (novelty) |
| `navigate` | target | reached target within tolerance |

### 2.2 How learning would work (concrete)

- **Skill library (disk-backed):** persist a per-skill success/failure history
  (like the Q-table but keyed by *skill* + *parameter signature*), so the bot
  remembers "gather(oak_log, 32) with a stone axe took 2 min and succeeded".
- **Intrinsic + extrinsic reward:** combine
  - *extrinsic* (task outcome: items gained, blocks placed, structure verified),
  - *intrinsic curiosity* (novelty: visiting unexplored chunks) for `explore`.
- **Curriculum / goal sampling:** in `training` mode, the bot self-selects goals
  from a **curriculum** ordered by difficulty (navigate → gather → mine → craft
  → build → farm), rather than only reacting to mobs.
- **Reuse the existing RL loop:** the reward/risk machinery in `RLAgent`
  (`calculateReward`, `assessRiskOutcome`, `calculateQValue`) can be generalized
  to skills; the Q-table becomes one *skill* Q-table plus the combat one.

## 3. Concrete code changes this would require

1. **`Action` space extension** — add skill-level actions (or a parallel
   `Skill` enum) so the RL loop can select `GATHER`, `CRAFT`, `BUILD`, etc.
2. **`GoalMapper` / `HybridPlanner` fix** — the action graph's
   `inferPreconditions`/`inferEffects` are too coarse (single keyword → single
   condition). Replace with a real goal→skill mapping table (we already know the
   9 goals and 12 tools — wire them explicitly).
3. **`AutonomousGoalEngine` JSON parsing** — add a strict-output prompt +
   `Gson` lenient mode + a regex fallback so prose like `["gather 32 wood",
   "craft a crafting table"]` is extracted instead of throwing
   `MalformedJsonException`.
4. **Reward for everyday outcomes** — extend `calculateReward` to read inventory
   deltas and block changes (the `State` already snapshots hotbar + surroundings).
5. **`training` mode curriculum loop** — a scheduler that, in training mode,
   periodically issues a sampled curriculum goal (not just on mob detection).

## 4. Phasing

- **Phase A (unblock correctness, no new ML):** fix the planner's goal→skill
  mapping and the LLM JSON parsing (items 2 & 3). This makes `play` mode
  actually *able* to do everyday tasks at all — prerequisite for any training.
- **Phase B (deterministic skill scripts):** encode the 7 skills as explicit
  procedures using the 12 tools + 24 primitives (no learning, just correct
  execution). Gives a baseline success signal to later learn *from*.
- **Phase C (learn from outcomes):** add skill-level reward + a skill Q-table,
  turn `training` mode into the curriculum sampler, persist and reuse in `play`.

## 5. Open questions for you

1. **Scope of "build":** do you want freeform "build me a living room", or a
   small catalog of *known* structures (walls, rooms, shelters) the bot can
   place and verify? Freeform is an LLM problem; a catalog is trainable.
2. **Persistence expectations:** should skill experience persist across server
   restarts like the combat Q-table does (yes, if we mirror `QTableStorage`)?
3. **Autonomy level:** in `training`, should the bot *self-direct* (pick its own
   curriculum goals) or should a player/console assign goals and the bot only
   learns the *execution*? Self-directed is more "buddy", but harder to debug.
