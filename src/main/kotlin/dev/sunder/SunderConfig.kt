package dev.sunder

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path

/**
 * On-disk config, JSON via the bundled Gson. One per-enchant value drives pull weight, XP cost,
 * durability loss and failure chance, each as `base + value * perValue`.
 */
class SunderConfig {

    /** Master on/off. */
    @JvmField var enabled: Boolean = true

    /** The block you interact with (holding item + book) to extract. */
    @JvmField var block: String = "minecraft:grindstone"

    /** If false, curse enchantments are never extractable (they're stuck to the item). */
    @JvmField var allowCurses: Boolean = false

    /**
     * Per-enchant "value". Higher = more likely to be the one pulled, costlier, riskier, more wear.
     * `_default` is used for any enchant not listed here.
     */
    @JvmField
    var enchantValues: MutableMap<String, Double> = linkedMapOf(
        "minecraft:mending" to 100.0,
        "minecraft:soul_speed" to 80.0,
        "minecraft:swift_sneak" to 80.0,
        "minecraft:silk_touch" to 70.0,
        "minecraft:infinity" to 65.0,
        "minecraft:fortune" to 60.0,
        "minecraft:looting" to 45.0,
        "minecraft:sharpness" to 30.0,
        "minecraft:protection" to 30.0,
        "minecraft:efficiency" to 25.0,
        "minecraft:unbreaking" to 15.0,
        "_default" to 20.0,
    )

    /** Optional exact XP-level overrides per enchant id (wins over the xp formula). */
    @JvmField
    var xpOverrides: MutableMap<String, Int> = linkedMapOf(
        "minecraft:mending" to 20,
    )

    // --- cost/risk formulas: cost = base + value * perValue ---

    /** XP levels = xpBase + value * xpPerValue (rounded). value 100 -> ~20 levels. */
    @JvmField var xpBase: Double = 2.0
    @JvmField var xpPerValue: Double = 0.18

    /** Fail chance = failBase + value * failPerValue, capped at failMax. value 100 -> ~0.40. */
    @JvmField var failBase: Double = 0.10
    @JvmField var failPerValue: Double = 0.003
    @JvmField var failMax: Double = 0.90

    /** Durability removed = (durBase + value * durPerValue)% of the item's MAX durability. value 100 -> ~55%. */
    @JvmField var durBasePercent: Double = 5.0
    @JvmField var durPerValue: Double = 0.5
    @JvmField var durMaxPercent: Double = 100.0

    // --- feedback ---

    @JvmField var sounds: Boolean = true
    @JvmField var particles: Boolean = true
    @JvmField var soundPreview: String = "minecraft:block.amethyst_block.hit"
    @JvmField var soundSuccess: String = "minecraft:block.enchantment_table.use"
    @JvmField var soundSuccessChime: String = "minecraft:entity.player.level_up"
    @JvmField var soundFail: String = "minecraft:entity.generic.explode"
    @JvmField var soundReject: String = "minecraft:entity.villager.no"

    // --- derived helpers ---

    fun valueOf(id: String): Double = enchantValues[id] ?: enchantValues["_default"] ?: 20.0

    fun xpCostFor(id: String): Int {
        xpOverrides[id]?.let { return it }
        return Math.round(xpBase + valueOf(id) * xpPerValue).toInt().coerceAtLeast(0)
    }

    fun failChanceFor(id: String): Double =
        (failBase + valueOf(id) * failPerValue).coerceIn(0.0, failMax)

    fun durabilityPercentFor(id: String): Double =
        (durBasePercent + valueOf(id) * durPerValue).coerceIn(0.0, durMaxPercent)

    fun save(path: Path) {
        Files.createDirectories(path.parent)
        Files.newBufferedWriter(path).use { GSON.toJson(this, it) }
    }

    companion object {
        private val GSON = GsonBuilder().setPrettyPrinting().create()

        fun load(path: Path): SunderConfig {
            if (Files.exists(path)) {
                runCatching {
                    Files.newBufferedReader(path).use { GSON.fromJson(it, SunderConfig::class.java) }
                }.getOrNull()?.let { return it }
            }
            val fresh = SunderConfig()
            runCatching { fresh.save(path) }
            return fresh
        }
    }
}
