package com.narek.autosell.hud;

import com.narek.autosell.AutoSellClient;
import com.narek.autosell.config.AutoSellConfig;
import com.narek.autosell.core.SellRoutine;
import com.narek.autosell.core.Walker;
import com.narek.autosell.fuel.FuelRoutine;
import com.narek.autosell.profit.ProfitTracker;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Compact status overlay.
 *
 * <p>Idle it is two lines; during a run it adds the phase, a progress meter and
 * a counts line. The goal is to answer "is it still working, and how much is
 * left" at a glance without covering the screen - so it stays under five short
 * lines and only shows what is currently relevant.
 */
public final class AutoSellHud implements HudElement {

	private static final int PAD = 3;
	private static final int LINE = 10;
	private static final int BG = 0x90000000;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREY = 0xFFAAAAAA;
	private static final int GREEN = 0xFF55FF55;
	private static final int YELLOW = 0xFFFFFF55;
	private static final int AQUA = 0xFF55FFFF;
	private static final int BAR_BG = 0xFF333333;
	private static final int BAR_FG = 0xFF55FF55;

	private static final int BAR_WIDTH = 96;
	private static final int BAR_HEIGHT = 3;

	private record Line(Component text, int colour) {
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		AutoSellConfig cfg = AutoSellConfig.get();
		if (!cfg.hudEnabled) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}

		FuelRoutine fuel = AutoSellClient.fuel();
		SellRoutine sell = AutoSellClient.routine();
		ProfitTracker profit = ProfitTracker.get();

		List<Line> lines = new ArrayList<>();
		float progress = -1F;

		if (fuel.isRunning()) {
			lines.add(new Line(Component.literal(
					"AutoSell  fuel " + (fuel.cycle() + 1) + "/" + fuel.maxCycles()), YELLOW));
			lines.add(new Line(Component.literal(
					phaseLine(FuelRoutine.describe(fuel.state()), fuel.walker())), AQUA));

			int bought = fuel.bonesBought();
			int left = fuel.bonesLeft();
			if (bought > 0) {
				progress = Math.min(1F, Math.max(0F, (bought - left) / (float) bought));
			}
			lines.add(new Line(Component.literal(
					"bones " + left + "  meal " + fuel.mealDropped()
							+ "  spent $" + compact(fuel.spent())), WHITE));

		} else if (sell.isRunning()) {
			lines.add(new Line(Component.literal(
					"AutoSell  sell cycle " + (sell.cycles() + 1)), YELLOW));
			lines.add(new Line(Component.literal(
					phaseLine(sellPhase(sell), sell.walker())), AQUA));
			lines.add(new Line(Component.literal(
					"session $" + compact(profit.sessionMoney())), GREEN));

		} else {
			lines.add(new Line(Component.literal("AutoSell"), YELLOW));
			lines.add(new Line(Component.literal(
					"session " + profit.formatSessionTotal()), GREEN));
			lines.add(new Line(Component.literal(
					profit.formatSessionItems() + " items / "
							+ profit.sessionSales() + " sales"), GREY));
		}

		render(graphics, mc.font, cfg, lines, progress);
	}

	private static void render(GuiGraphicsExtractor graphics, Font font,
			AutoSellConfig cfg, List<Line> lines, float progress) {
		int width = BAR_WIDTH;
		for (Line line : lines) {
			width = Math.max(width, font.width(line.text()));
		}
		int height = lines.size() * LINE + (progress >= 0 ? BAR_HEIGHT + 2 : 0);

		int x = cfg.hudX;
		int y = cfg.hudY;
		graphics.fill(x, y, x + width + PAD * 2, y + height + PAD * 2, BG);

		int tx = x + PAD;
		int ty = y + PAD;
		for (Line line : lines) {
			graphics.text(font, line.text(), tx, ty, line.colour());
			ty += LINE;
		}

		if (progress >= 0) {
			int filled = Math.round(width * progress);
			graphics.fill(tx, ty, tx + width, ty + BAR_HEIGHT, BAR_BG);
			if (filled > 0) {
				graphics.fill(tx, ty, tx + filled, ty + BAR_HEIGHT, BAR_FG);
			}
		}
	}

	/** Phase plus a short movement hint, when moving. */
	private static String phaseLine(String phase, Walker walker) {
		if (!walker.isActive()) {
			return phase;
		}
		StringBuilder sb = new StringBuilder(phase);
		double left = walker.distanceRemaining();
		if (left >= 0) {
			sb.append(String.format(" %.0fm", left));
		}
		if (walker.isReplanning()) {
			sb.append(" ...");
		} else if (walker.isDelegated()) {
			sb.append(" >");
		}
		return sb.toString();
	}

	private static String sellPhase(SellRoutine routine) {
		return switch (routine.state()) {
			case PRE_CHECK -> "checking inventory";
			case DUMP_WALK -> "to dump chest";
			case DUMP_OPEN, DUMP_AWAIT -> "opening dump";
			case DUMP_DEPOSIT -> "depositing";
			case DUMP_CLOSE -> "closing";
			case RETURN_WALK -> "back to loot chest";
			case OPEN_CHEST, AWAIT_CONTAINER -> "opening chest";
			case LOOT -> "looting";
			case CLOSE_CONTAINER -> "closing";
			case VERIFY -> "verifying";
			case SELL, AWAIT_SELL -> "selling";
			default -> "working";
		};
	}

	/** Money without the noise: 19.9k, 1.24M. */
	private static String compact(double value) {
		if (value >= 1_000_000) {
			return String.format("%.2fM", value / 1_000_000);
		}
		if (value >= 1_000) {
			return String.format("%.1fk", value / 1_000);
		}
		return String.format("%.0f", value);
	}
}
