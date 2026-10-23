package com.autolumber.config;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/** Every kind of tree log the bot can be told to look for. */
public enum LogType {
	OAK("Oak", Items.OAK_SAPLING, Blocks.OAK_LOG, Blocks.OAK_WOOD),
	SPRUCE("Spruce", Items.SPRUCE_SAPLING, Blocks.SPRUCE_LOG, Blocks.SPRUCE_WOOD),
	BIRCH("Birch", Items.BIRCH_SAPLING, Blocks.BIRCH_LOG, Blocks.BIRCH_WOOD),
	JUNGLE("Jungle", Items.JUNGLE_SAPLING, Blocks.JUNGLE_LOG, Blocks.JUNGLE_WOOD),
	ACACIA("Acacia", Items.ACACIA_SAPLING, Blocks.ACACIA_LOG, Blocks.ACACIA_WOOD),
	DARK_OAK("Dark Oak", null, Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_WOOD),
	MANGROVE("Mangrove", Items.MANGROVE_PROPAGULE, Blocks.MANGROVE_LOG, Blocks.MANGROVE_WOOD),
	CHERRY("Cherry", Items.CHERRY_SAPLING, Blocks.CHERRY_LOG, Blocks.CHERRY_WOOD),
	PALE_OAK("Pale Oak", null, Blocks.PALE_OAK_LOG, Blocks.PALE_OAK_WOOD),
	POPLAR("Poplar", Items.POPLAR_SAPLING, Blocks.POPLAR_LOG, Blocks.POPLAR_WOOD),
	CRIMSON("Crimson Stem", null, Blocks.CRIMSON_STEM, Blocks.CRIMSON_HYPHAE),
	WARPED("Warped Stem", null, Blocks.WARPED_STEM, Blocks.WARPED_HYPHAE);

	private final String label;
	private final Set<Block> blocks;
	private final Item sapling;

	LogType(String label, Item sapling, Block... blocks) {
		this.label = label;
		this.sapling = sapling;
		this.blocks = Set.of(blocks);
	}

	/** Sapling to replant with, or null if this tree can't be replanted from one sapling. */
	public Item sapling() {
		return sapling;
	}

	public String label() {
		return label;
	}

	public boolean matches(BlockState state) {
		return blocks.contains(state.getBlock());
	}

	/** Returns the log type of this block state, or null if it isn't a tree log. */
	public static LogType of(BlockState state) {
		for (LogType t : values()) {
			if (t.matches(state)) return t;
		}
		return null;
	}
}
