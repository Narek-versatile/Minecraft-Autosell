package com.narek.autosell.fuel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.narek.autosell.AutoSellClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A recorded shop interaction, split by what each click actually did.
 *
 * <p>Recording raw clicks and replaying them verbatim was wrong: the quantity
 * buttons got captured as navigation, which hardcoded whatever amount happened
 * to be clicked during recording. Instead each click is classified, and the
 * quantity buttons keep the measured effect they had on the counter, so replay
 * can reach any target amount rather than repeating one improvised sequence.
 */
public class ShopPath {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE =
			FabricLoader.getInstance().getConfigDir().resolve("autosell-shoppath.json");

	public enum Kind {
		/** Moves to another screen. Replayed in order. */
		NAVIGATE,
		/** Changes the amount by {@link Step#delta}. Replayed as needed. */
		ADJUST,
		/** Completes the purchase. Replayed last, after the price check. */
		PURCHASE
	}

	public static class Step {
		public Kind kind = Kind.NAVIGATE;
		/** Normalized title of the screen this click was made on. */
		public String screenTitle = "";
		public int slot;
		public int button;
		public String itemId = "";
		/** Folded label, for matching. */
		public String itemName = "";
		/** Exactly what the server sent, for display. */
		public String itemNameRaw = "";
		/** For ADJUST: measured change in the amount counter. */
		public int delta;

		public String label() {
			return itemNameRaw.isEmpty() ? itemName : itemNameRaw;
		}

		public String describe() {
			String base = "\"" + label() + "\" (slot " + slot + ")";
			return switch (kind) {
				case ADJUST -> base + "  " + (delta > 0 ? "+" : "") + delta + " stacks";
				case PURCHASE -> base + "  <- purchase";
				case NAVIGATE -> base + " on \"" + screenTitle + "\"";
			};
		}
	}

	public List<Step> steps = new ArrayList<>();

	public boolean isEmpty() {
		return steps == null || steps.isEmpty();
	}

	public int size() {
		return steps == null ? 0 : steps.size();
	}

	public List<Step> of(Kind kind) {
		List<Step> out = new ArrayList<>();
		for (Step s : steps) {
			if (s.kind == kind) {
				out.add(s);
			}
		}
		return out;
	}

	public List<Step> navigationSteps() {
		return of(Kind.NAVIGATE);
	}

	/** Adjusters that add, largest step first. */
	public List<Step> adjusters() {
		List<Step> out = of(Kind.ADJUST);
		out.removeIf(s -> s.delta == 0);
		out.sort((a, b) -> Integer.compare(Math.abs(b.delta), Math.abs(a.delta)));
		return out;
	}

	public Step confirmStep() {
		for (Step s : steps) {
			if (s.kind == Kind.PURCHASE) {
				return s;
			}
		}
		return null;
	}

	/** True when the path can actually be replayed. */
	public boolean isUsable() {
		return !isEmpty() && confirmStep() != null;
	}

	public static ShopPath load() {
		if (Files.exists(FILE)) {
			try {
				ShopPath path = GSON.fromJson(
						Files.readString(FILE, StandardCharsets.UTF_8), ShopPath.class);
				if (path != null && path.steps != null) {
					for (Step s : path.steps) {
						if (s.kind == null) {
							s.kind = Kind.NAVIGATE;
						}
					}
					return path;
				}
			} catch (Exception e) {
				AutoSellClient.LOGGER.error("[autosell] could not read shop path", e);
			}
		}
		ShopPath bundled = fromBundledDefaults();
		if (bundled != null) {
			bundled.save();
			return bundled;
		}
		return new ShopPath();
	}

	/**
	 * A shop path shipped inside the jar, used when none has been recorded.
	 *
	 * <p>The recorded path is the hard part of setup - it needs the shop opened
	 * and clicked through by hand - so carrying it in the jar means a fresh
	 * install is ready without repeating that.
	 */
	private static ShopPath fromBundledDefaults() {
		try (var in = ShopPath.class.getResourceAsStream(
				"/assets/autosell/default-shoppath.json")) {
			if (in == null) {
				return null;
			}
			ShopPath path = GSON.fromJson(
					new String(in.readAllBytes(), StandardCharsets.UTF_8), ShopPath.class);
			if (path == null || path.steps == null || path.steps.isEmpty()) {
				return null;
			}
			for (Step step : path.steps) {
				if (step.kind == null) {
					step.kind = Kind.NAVIGATE;
				}
			}
			AutoSellClient.LOGGER.info("[autosell] seeded shop path from bundled defaults");
			return path;
		} catch (Exception e) {
			AutoSellClient.LOGGER.warn("[autosell] could not read bundled shop path", e);
			return null;
		}
	}

	public void save() {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			AutoSellClient.LOGGER.error("[autosell] could not write shop path", e);
		}
	}

	public static Path file() {
		return FILE;
	}
}
