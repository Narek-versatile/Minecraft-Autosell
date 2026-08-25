package com.narek.autosell.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * User-facing settings, persisted to config/autosell.json.
 *
 * <p>Everything here is editable in-game (see {@code /autosell}) or via the
 * config screen; the file is only a persistence format, not the primary UI.
 */
public class AutoSellConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH =
			FabricLoader.getInstance().getConfigDir().resolve("autosell.json");

	private static AutoSellConfig instance;

	/** Item ids (e.g. "minecraft:cobblestone") that may be looted and sold. */
	public Set<String> sellList = new LinkedHashSet<>();

	/** GLFW key code for the trigger. Default GLFW_KEY_1. */
	public int triggerKey = GLFW.GLFW_KEY_1;

	/** All three presses must land inside this window, in milliseconds. */
	public int tripleWindowMs = 600;

	/** Client ticks to wait between container clicks (1 tick = 50 ms). */
	public int clickDelayTicks = 2;

	/** Keep pulling from the chest until it holds no more whitelisted items. */
	public boolean loopUntilEmpty = true;

	/** Runaway guard: never run more than this many sell cycles per activation. */
	public int maxCycles = 16;

	/** Sent without the leading slash. */
	public String sellCommand = "sellall inventory";

	/** Group 1 = item count, group 2 = money. Tuned for PlanetPVP by default. */
	public String sellRegex = "sold\\s+([\\d,]+)\\s+items?\\s+for\\s+\\$([\\d,]+(?:\\.\\d+)?)";

	/** Ticks to wait for the server's sell confirmation before giving up. */
	public int sellTimeoutTicks = 100;

	/** Ticks to wait for a container GUI to open after right-clicking. */
	public int containerTimeoutTicks = 60;

	/** Where to walk when trash needs dropping off. Null until set. */
	public Integer dumpX;
	public Integer dumpY;
	public Integer dumpZ;
	public String dumpDimension;

	/** The loot chest, remembered so the mod can walk back to it. */
	public Integer lootX;
	public Integer lootY;
	public Integer lootZ;

	/**
	 * Gear that is never deposited and never counts as trash, wherever it sits.
	 * Armor and offhand are protected regardless of this list.
	 */
	public Set<String> keepList = new LinkedHashSet<>();

	/**
	 * Fire every matching transfer in one tick, the way vanilla's
	 * shift+double-click does, instead of pacing one per clickDelayTicks.
	 */
	public boolean burstLoot = true;

	/** Cap on transfers per burst, so one tick cannot spam unbounded packets. */
	public int burstMaxClicks = 64;

	/** Walk to the dump chest automatically when trash is found. */
	public boolean walkEnabled = true;

	/** Give up walking after this many ticks. */
	public int walkTimeoutTicks = 400;

	/** Stop walking once within this many blocks of the target. */
	public double reachDistance = 3.0;

	/** Sprint while walking between chests. */
	public boolean walkSprint = true;

	// -- fuel cycle --------------------------------------------------------

	/** Farm spot to stand on while crafting and dropping. */
	public Integer farmX;
	public Integer farmY;
	public Integer farmZ;

	/**
	 * Which way to face when dropping. Thrown items fly in the direction the
	 * player looks, so the delivery system only catches them if the facing
	 * matches - the coordinates alone are not enough.
	 */
	public Float farmYaw;
	public Float farmPitch;

	/**
	 * Exact stand position, not just the block. Throw arcs start from the
	 * player's precise location, so landing anywhere in the right block is not
	 * close enough for a collector.
	 */
	public Double farmPosX;
	public Double farmPosY;
	public Double farmPosZ;

	/**
	 * Ticks between crafting and dropping actions. Kept separate from
	 * clickDelayTicks so the craft/drop loop can run flat out while container
	 * navigation stays paced.
	 */
	public int craftDelayTicks = 1;

	/** Opens the shop. Sent without the leading slash. */
	public String shopCommand = "shop";

	/** Bought item, and what it crafts into. */
	public String fuelSourceItem = "minecraft:bone";
	public String fuelProductItem = "minecraft:bone_meal";

	/** Slots left free when sizing the purchase, as crafting headroom. */
	public int fuelReserveSlots = 6;

	/** Stacks to buy per cycle. Zero sizes it from free space instead. */
	public int fuelFixedStacks = 0;

	/** Stop after this many buy/craft/drop cycles. */
	public int fuelMaxCycles = 10;

	/** Stop when balance would fall below this. Zero disables the check. */
	public double fuelMoneyFloor = 0;

	/**
	 * Hard ceiling on a single purchase. The confirm button's price is parsed
	 * and checked against this before clicking - the last line of defence
	 * against a mis-read slot emptying the account.
	 */
	public double fuelMaxSpend = 20000;

	/** Sidebar objective whose value is the balance. Matched case-insensitively. */
	public String balanceLabel = "BALANCE";

	// The shop path is recorded by clicking it once, not configured here:
	// the server labels its UI in Unicode small capitals, which cannot be typed
	// into a config field. See ShopRecorder / autosell-shoppath.json.

	/** Key to snapshot the open GUI, usable while a container has focus. */
	public int inspectKey = org.lwjgl.glfw.GLFW.GLFW_KEY_P;

	/** Log every server GUI that opens to autosell-guidump.txt. */
	public boolean guiLogging = true;

	/** Ticks to wait for each shop screen to appear. */
	public int shopScreenTimeoutTicks = 100;

	public boolean hudEnabled = true;
	public int hudX = 4;
	public int hudY = 4;

	/** Append each sale to config/autosell-log.csv. */
	public boolean csvLogEnabled = true;

	public static AutoSellConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static AutoSellConfig load() {
		if (Files.exists(PATH)) {
			try {
				String json = Files.readString(PATH, StandardCharsets.UTF_8);
				AutoSellConfig cfg = GSON.fromJson(json, AutoSellConfig.class);
				if (cfg != null) {
					if (cfg.sellList == null) cfg.sellList = new LinkedHashSet<>();
					if (cfg.keepList == null) cfg.keepList = new LinkedHashSet<>();

					return cfg;
				}
			} catch (Exception e) {
				// Corrupt or hand-edited into invalid JSON: fall back to defaults
				// rather than preventing the client from starting.
				com.narek.autosell.AutoSellClient.LOGGER
						.error("[autosell] could not read config, using defaults", e);
			}
		}
		AutoSellConfig cfg = fromBundledDefaults();
		if (cfg == null) {
			cfg = new AutoSellConfig();
		}
		cfg.save();
		return cfg;
	}

	/**
	 * Settings shipped inside the jar, used when no config file exists yet.
	 *
	 * <p>Lets the mod arrive already knowing the chest positions, farm spot and
	 * facing, so a fresh install is immediately usable instead of needing the
	 * whole setup repeated. The on-disk file always wins once it exists.
	 */
	private static AutoSellConfig fromBundledDefaults() {
		try (var in = AutoSellConfig.class.getResourceAsStream(
				"/assets/autosell/default-config.json")) {
			if (in == null) {
				return null;
			}
			String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			AutoSellConfig cfg = GSON.fromJson(json, AutoSellConfig.class);
			if (cfg == null) {
				return null;
			}
			if (cfg.sellList == null) cfg.sellList = new LinkedHashSet<>();
			if (cfg.keepList == null) cfg.keepList = new LinkedHashSet<>();
			com.narek.autosell.AutoSellClient.LOGGER.info(
					"[autosell] seeded config from bundled defaults");
			return cfg;
		} catch (Exception e) {
			com.narek.autosell.AutoSellClient.LOGGER.warn(
					"[autosell] could not read bundled defaults", e);
			return null;
		}
	}

	public void save() {
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			com.narek.autosell.AutoSellClient.LOGGER
					.error("[autosell] could not write config", e);
		}
	}

	public boolean hasDumpChest() {
		return dumpX != null && dumpY != null && dumpZ != null;
	}

	public void setDumpChest(int x, int y, int z, String dimension) {
		this.dumpX = x;
		this.dumpY = y;
		this.dumpZ = z;
		this.dumpDimension = dimension;
		save();
	}

	public boolean hasFarmSpot() {
		return farmX != null && farmY != null && farmZ != null;
	}

	public void setFarmSpot(int x, int y, int z, float yaw, float pitch,
			double px, double py, double pz) {
		this.farmX = x;
		this.farmY = y;
		this.farmZ = z;
		this.farmYaw = yaw;
		this.farmPitch = pitch;
		this.farmPosX = px;
		this.farmPosY = py;
		this.farmPosZ = pz;
		save();
	}

	public boolean hasFarmPrecise() {
		return farmPosX != null && farmPosY != null && farmPosZ != null;
	}

	public boolean hasFarmFacing() {
		return farmYaw != null && farmPitch != null;
	}

	public boolean hasLootChest() {
		return lootX != null && lootY != null && lootZ != null;
	}

	public void setLootChest(int x, int y, int z) {
		this.lootX = x;
		this.lootY = y;
		this.lootZ = z;
		save();
	}

	public void clearDumpChest() {
		this.dumpX = null;
		this.dumpY = null;
		this.dumpZ = null;
		this.dumpDimension = null;
		save();
	}
}
