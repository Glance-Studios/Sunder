package dev.sunder

import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.ItemEnchantments

/**
 * The extraction ritual. One enchant is pulled into a book, weighted toward high-value ones, at
 * the cost of XP and a chunk of max durability, with a real chance of destroying item and book.
 */
object Disenchant {

    private val CURSE_IDS = setOf("minecraft:binding_curse", "minecraft:vanishing_curse")

    private data class Candidate(
        val holder: Holder<Enchantment>,
        val level: Int,
        val id: String,
        val value: Double,
    )

    fun handle(player: ServerPlayer, level: ServerLevel, pos: BlockPos): InteractionResult {
        val cfg = SunderMod.config

        // Identify item + plain book across the two hands (order-flexible).
        val main = player.getItemInHand(InteractionHand.MAIN_HAND)
        val off = player.getItemInHand(InteractionHand.OFF_HAND)
        val itemHand: InteractionHand
        val bookHand: InteractionHand
        val item: ItemStack
        val book: ItemStack
        when {
            isSource(main) && isPlainBook(off) -> { item = main; itemHand = InteractionHand.MAIN_HAND; book = off; bookHand = InteractionHand.OFF_HAND }
            isSource(off) && isPlainBook(main) -> { item = off; itemHand = InteractionHand.OFF_HAND; book = main; bookHand = InteractionHand.MAIN_HAND }
            else -> return InteractionResult.PASS // not our combo -> let the grindstone open normally
        }

        val candidates = extractable(item, cfg)
        if (candidates.isEmpty()) {
            reject(player, level, pos, "Nothing extractable on that item.")
            return InteractionResult.FAIL
        }

        // Sneak = preview (nothing consumed), based on the most-likely (highest-value) enchant.
        if (player.isShiftKeyDown) {
            showPreview(player, level, pos, item, candidates, cfg)
            return InteractionResult.FAIL
        }

        val pick = weightedPick(candidates, level.random.nextDouble())
        val xpCost = cfg.xpCostFor(pick.id)
        if (player.experienceLevel < xpCost) {
            reject(player, level, pos, "Not enough XP (need $xpCost levels).")
            return InteractionResult.FAIL
        }
        player.giveExperienceLevels(-xpCost) // cost is paid whether it succeeds or blows up

        if (level.random.nextDouble() < cfg.failChanceFor(pick.id)) {
            onFailure(player, level, pos, itemHand, bookHand)
            return InteractionResult.FAIL
        }

        onSuccess(player, level, pos, item, itemHand, book, bookHand, pick, cfg)
        return InteractionResult.FAIL
    }

    // --- eligibility ---

    private fun isSource(s: ItemStack): Boolean = !s.isEmpty && s.isDamageableItem && s.isEnchanted
    private fun isPlainBook(s: ItemStack): Boolean = !s.isEmpty && s.item === Items.BOOK

    private fun extractable(item: ItemStack, cfg: SunderConfig): List<Candidate> {
        val out = ArrayList<Candidate>()
        for (e in item.enchantments.entrySet()) {
            val holder = e.key
            val id = holder.registeredName
            if (!cfg.allowCurses && id in CURSE_IDS) continue
            out += Candidate(holder, e.intValue, id, cfg.valueOf(id))
        }
        return out
    }

    private fun weightedPick(cands: List<Candidate>, roll01: Double): Candidate {
        val total = cands.sumOf { it.value }
        if (total <= 0.0) return cands[0]
        var r = roll01 * total
        for (c in cands) {
            r -= c.value
            if (r <= 0.0) return c
        }
        return cands.last()
    }

    // --- outcomes ---

    private fun onSuccess(
        player: ServerPlayer, level: ServerLevel, pos: BlockPos,
        item: ItemStack, itemHand: InteractionHand, book: ItemStack, bookHand: InteractionHand,
        pick: Candidate, cfg: SunderConfig,
    ) {
        // Produce the enchanted book first, so a shattered item still yields the extraction.
        val outBook = ItemStack(Items.ENCHANTED_BOOK)
        val stored = ItemEnchantments.Mutable(ItemEnchantments.EMPTY)
        stored.set(pick.holder, pick.level)
        outBook.set(DataComponents.STORED_ENCHANTMENTS, stored.toImmutable())
        if (!player.inventory.add(outBook)) player.drop(outBook, false)

        book.shrink(1)
        player.setItemInHand(bookHand, book)

        EnchantmentHelper.updateEnchantments(item) { m -> m.removeIf { h -> h === pick.holder } }

        // Wear is a percent of max durability, so the hit can shatter the item outright.
        val maxDmg = item.maxDamage
        val hit = Math.round(maxDmg * cfg.durabilityPercentFor(pick.id) / 100.0).toInt().coerceAtLeast(1)
        val broke: Boolean
        if (item.damageValue + hit >= maxDmg) {
            item.shrink(item.count) // shatter
            broke = true
        } else {
            item.damageValue = item.damageValue + hit
            broke = false
        }
        player.setItemInHand(itemHand, item)
        player.inventoryMenu.broadcastChanges()

        val name = prettify(pick.id)
        val tail = if (broke) "the item shattered" else "item worn ${wearTier(cfg.durabilityPercentFor(pick.id)).lowercase()}"
        player.sendSystemMessage(Component.literal("§aExtracted §f$name §ainto a book. §7(-${cfg.xpCostFor(pick.id)} levels, $tail)"))
        playSound(level, pos, cfg.soundSuccess, 0.8f, 1.0f)
        playSound(level, pos, cfg.soundSuccessChime, 0.6f, 1.4f)
        if (cfg.particles) level.sendParticles(ParticleTypes.ENCHANT, pos.x + 0.5, pos.y + 1.1, pos.z + 0.5, 40, 0.4, 0.4, 0.4, 0.6)
    }

    private fun onFailure(
        player: ServerPlayer, level: ServerLevel, pos: BlockPos,
        itemHand: InteractionHand, bookHand: InteractionHand,
    ) {
        player.setItemInHand(itemHand, ItemStack(Items.GUNPOWDER))
        player.setItemInHand(bookHand, ItemStack(Items.GUNPOWDER))
        player.inventoryMenu.broadcastChanges()

        player.sendSystemMessage(Component.literal("§cThe extraction failed! The item and book were destroyed."))
        playSound(level, pos, SunderMod.config.soundFail, 1.0f, 1.0f)
        if (SunderMod.config.particles) level.sendParticles(ParticleTypes.SMOKE, pos.x + 0.5, pos.y + 1.0, pos.z + 0.5, 30, 0.3, 0.3, 0.3, 0.05)
    }

    private fun showPreview(
        player: ServerPlayer, level: ServerLevel, pos: BlockPos,
        item: ItemStack, candidates: List<Candidate>, cfg: SunderConfig,
    ) {
        val likely = candidates.maxByOrNull { it.value }!!
        val xp = cfg.xpCostFor(likely.id)
        player.sendSystemMessage(Component.literal("§6Extraction preview: §f${item.hoverName.string}"))
        player.sendSystemMessage(Component.literal("§7Likely yields: §f${valueTier(likely.value)} enchant"))
        player.sendSystemMessage(Component.literal("§7Risk: §f${riskTier(cfg.failChanceFor(likely.id))} §8(failure destroys both)"))
        player.sendSystemMessage(Component.literal("§7Wear: §f${wearTier(cfg.durabilityPercentFor(likely.id))}"))
        player.sendSystemMessage(Component.literal("§7Cost: §f$xp levels"))
        playSound(level, pos, cfg.soundPreview, 0.6f, 1.0f)
    }

    private fun reject(player: ServerPlayer, level: ServerLevel, pos: BlockPos, msg: String) {
        player.sendOverlayMessage(Component.literal("§c$msg"))
        playSound(level, pos, SunderMod.config.soundReject, 0.7f, 1.0f)
    }

    // --- helpers ---

    private fun playSound(level: ServerLevel, pos: BlockPos, id: String, vol: Float, pitch: Float) {
        if (!SunderMod.config.sounds) return
        val loc = Identifier.tryParse(id) ?: return
        val se = BuiltInRegistries.SOUND_EVENT.getOptional(loc).orElse(null) ?: return
        level.playSound(null, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, se, SoundSource.BLOCKS, vol, pitch)
    }

    private fun prettify(id: String): String =
        id.substringAfter(':').replace('_', ' ').split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    private fun valueTier(v: Double) = when { v >= 60 -> "a high-value"; v >= 30 -> "a moderate"; else -> "a minor" }
    private fun riskTier(p: Double) = when { p < 0.15 -> "Low"; p < 0.35 -> "Moderate"; p < 0.60 -> "High"; else -> "Severe" }
    private fun wearTier(p: Double) = when { p < 15 -> "Light"; p < 35 -> "Moderate"; p < 60 -> "Heavy"; else -> "Severe" }
}
