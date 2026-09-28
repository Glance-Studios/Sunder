# Sunder

Enchantment extraction at a grindstone, with a real chance of losing everything. Hold an enchanted
damageable item in one hand and a plain book in the other, use a grindstone, and one enchantment is
wrenched out into a book - for XP levels, a chunk of the item's durability, and the possibility that
item and book both shatter and leave you holding gunpowder.

Sneak and use to preview the cost. Click without sneaking to commit.

One number per enchantment drives all of it. An enchantment's `value` sets how likely it is to be
the one pulled, how many levels it costs, how much durability comes off, and how likely the whole
thing detonates. Mending sits at 100 and Unbreaking at 15, so pulling Mending off a netherite
pickaxe is a 20-level, 55%-durability gamble at 40% failure, while Unbreaking is 5 levels and a
12.5% scratch at 14.5%. Every curve is `base + value * perValue`, so the tuning is four numbers, not
a table of special cases.

## Server-side only - players install nothing

This runs on the server. It uses vanilla items, a vanilla block and a vanilla command, and registers
no new content, so a vanilla client connects and plays normally.

## Requirements

Drop these into your **server's** `mods/` folder:

| Mod | Version |
| --- | --- |
| `sunder-0.1.0.jar` | this mod |
| [Fabric API](https://modrinth.com/mod/fabric-api) | `0.155.2+26.2` (or compatible) |
| [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) | `1.13.12+kotlin.2.4.0` (or compatible) |

Minecraft **26.2**, Fabric Loader **0.19.3+**, **Java 25**.

## Install

1. Put the three jars in `<server>/mods/`.
2. Start the server once. A default config is written to `<server>/config/sunder.json`.
3. Edit it and `/sunder reload`.

## What a player sees

Sneak-use with the right combination and you get the shape of the deal, not the exact odds:

```
Extraction preview: Netherite Pickaxe
Likely yields: a high-value enchant
Risk: High (failure destroys both)
Wear: Severe
Cost: 20 levels
```

Risk and wear are tiers rather than percentages, so the gamble stays a gamble. The XP cost is exact,
because you can't consent to a price you can't see.

Commit, and it either works:

```
Extracted Mending into a book. (-20 levels, item worn severe)
```

or it doesn't:

```
The extraction failed! The item and book were destroyed.
```

The XP is spent either way. Which enchantment comes out is a weighted roll, not a choice - a pickaxe
with Mending, Efficiency and Unbreaking will usually give up Mending, but not always.

## Config (`config/sunder.json`)

JSON, via the Gson that Minecraft already ships.

```json
{
  "enabled": true,
  "block": "minecraft:grindstone",
  "allowCurses": false,
  "enchantValues": {
    "minecraft:mending": 100.0,
    "minecraft:silk_touch": 70.0,
    "minecraft:unbreaking": 15.0,
    "_default": 20.0
  },
  "xpOverrides": { "minecraft:mending": 20 },
  "xpBase": 2.0,      "xpPerValue": 0.18,
  "failBase": 0.10,   "failPerValue": 0.003,  "failMax": 0.90,
  "durBasePercent": 5.0, "durPerValue": 0.5,  "durMaxPercent": 100.0
}
```

| Setting | Meaning |
| --- | --- |
| `block` | what you interact with; any block id works, not just the grindstone |
| `allowCurses` | whether Binding and Vanishing can be pulled at all |
| `enchantValues` | per-enchantment value; `_default` covers anything unlisted |
| `xpOverrides` | exact level cost for one enchantment, ignoring the xp formula |
| `xpBase` / `xpPerValue` | levels = `base + value * perValue`, rounded |
| `failBase` / `failPerValue` / `failMax` | failure chance, capped |
| `durBasePercent` / `durPerValue` / `durMaxPercent` | durability removed, as a percent of the item's max |

Durability is a percent of maximum, not a flat number, so the same extraction costs a netherite
pickaxe and a wooden one the same fraction of their life. If the hit would exceed what's left, the
item shatters.

Sounds and particles are configurable too - `sounds`, `particles`, and five `sound*` keys taking
sound event ids.

## Commands (`/sunder`, op level 2)

```
/sunder reload    re-read config/sunder.json
/sunder toggle    turn extraction on or off, and persist it
```

## Limits

Enchanted books are ignored as a source; only enchantments on gear count. The extracted book carries
the enchantment at its original level, so this moves enchantments rather than diluting them. There is
no way to pick which enchantment comes out, by design.
