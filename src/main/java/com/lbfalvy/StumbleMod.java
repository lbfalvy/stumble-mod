package com.lbfalvy;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.HashMap;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StumbleMod implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("stumble");

	private record BlockData(BlockPos pos, BlockState state) {
	}

	// If the player is standing on stair blocks, return the corresponding block
	// state. Doesn't detect shifting on the side of a stair block.
	final static Optional<BlockData> getStairsUnderPlayer(ServerPlayerEntity player) {
		var standing_on = player.world.getBlockState(player.getBlockPos().down());
		if (!standing_on.isAir() && standing_on.getBlock() instanceof StairsBlock)
			return Optional.of(new BlockData(player.getBlockPos().down(), standing_on));
		return Optional.empty();
	}

	// Decides if a player should start slipping in combination with playerCanSlip.
	final static boolean playerShouldSlip(ServerPlayerEntity player) {
		return player.isOnGround() && player.isSprinting();
	}

	// Decides if a player is able to slip off of the block they are currently
	// standing on, and if so, in which direction. Players continue to slip as long
	// as they can after playerShouldSlip no longer returns true.
	final static Optional<Integer> playerCanSlip(ServerPlayerEntity player) {
		return getStairsUnderPlayer(player)
				.filter(stairs -> stairs.state().get(StairsBlock.HALF) == BlockHalf.BOTTOM)
				.flatMap(stairs -> {
					var facing = stairs.state().get(StairsBlock.FACING).getOpposite();
					var neighbour_pos = stairs.pos().offset(facing);
					if (player.world.isAir(neighbour_pos) && player.world.isAir(neighbour_pos.up()))
						return Optional.of(facing.getHorizontal());
					return Optional.empty();
				});
	}

	// Enact the player slipping off of the block they're currently standing on in
	// the specified direction.
	final static void doPlayerSlip(ServerPlayerEntity player, int direction) {
		var target = player.getBlockPos().offset(Direction.fromHorizontal(direction)).down();
		player.teleport((double) target.getX() + 0.5d, target.getY(), (double) target.getZ() + 0.5d, false);
		// timeUntilRegen > 10 is used in LivingEntity::damage to implement the
		// cooldown. This should be reset when the damage is applied anyway.
		player.timeUntilRegen = 9;
		player.damage(DamageSource.FALL, 1);
	}

	// slipping players mapped to the next time they should slip
	private static final HashMap<ServerPlayerEntity, Long> SLIPPING_AT = new HashMap<>();
	// wait at least this many ticks on each stair. High values give the player a
	// chance to break their fall, very low values make the fall unnaturally fast
	private static final long DEBOUNCE = 1;

	@Override
	public void onInitialize() {
		// on each tick
		ServerTickEvents.END_WORLD_TICK.register(world -> {
			var time = world.getTime();
			// record players who lost their balance on this tick
			for (var player : world.getPlayers())
				if (!SLIPPING_AT.containsKey(player) && playerShouldSlip(player))
					SLIPPING_AT.put(player, time);
			// process all stumbling players whose debounce expired
			for (var player : SLIPPING_AT.keySet())
				if (SLIPPING_AT.get(player) < time)
					playerCanSlip(player).ifPresentOrElse(
							direction -> {
								// if they can slip, make them do so and schedule next check
								doPlayerSlip(player, direction);
								SLIPPING_AT.put(player, time + DEBOUNCE);
							},
							() -> {
								// if they cannot slip anywhere, allow them to recover
								SLIPPING_AT.remove(player);
							});
		});
	}
}