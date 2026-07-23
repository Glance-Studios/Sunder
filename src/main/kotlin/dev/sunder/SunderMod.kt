package dev.sunder

import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.commands.Commands
import net.minecraft.commands.Commands.literal
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Risky enchantment extraction at a grindstone. Hold an enchanted damageable item in one hand
 * and a plain book in the other, then sneak to preview or click to gamble.
 */
object SunderMod : DedicatedServerModInitializer {

    val LOG = LoggerFactory.getLogger("sunder")

    lateinit var config: SunderConfig
        private set

    private lateinit var configPath: Path

    override fun onInitializeServer() {
        configPath = FabricLoader.getInstance().configDir.resolve("sunder.json")
        config = SunderConfig.load(configPath)

        UseBlockCallback.EVENT.register(UseBlockCallback { player, world, hand, hit ->
            if (world.isClientSide || hand != InteractionHand.MAIN_HAND) return@UseBlockCallback InteractionResult.PASS
            if (!config.enabled) return@UseBlockCallback InteractionResult.PASS
            val sp = player as? ServerPlayer ?: return@UseBlockCallback InteractionResult.PASS
            val level = world as? ServerLevel ?: return@UseBlockCallback InteractionResult.PASS
            val pos = hit.blockPos
            val blockId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).block).toString()
            if (blockId != config.block) return@UseBlockCallback InteractionResult.PASS
            Disenchant.handle(sp, level, pos)
        })

        registerCommand()
        LOG.info("Sunder enabled={} block={}", config.enabled, config.block)
    }

    private fun registerCommand() {
        CommandRegistrationCallback.EVENT.register(CommandRegistrationCallback { dispatcher, _, _ ->
            dispatcher.register(
                literal("sunder")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)) // op level 2
                    .then(
                        literal("reload").executes {
                            config = SunderConfig.load(configPath)
                            it.source.sendSuccess({ Component.literal("[Sunder] config reloaded.") }, false)
                            1
                        },
                    )
                    .then(
                        literal("toggle").executes {
                            config.enabled = !config.enabled
                            config.save(configPath)
                            it.source.sendSuccess({ Component.literal("[Sunder] enabled=${config.enabled}") }, false)
                            1
                        },
                    ),
            )
        })
    }
}
