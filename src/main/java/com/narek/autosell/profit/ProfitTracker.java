package com.narek.autosell.profit;

import com.narek.autosell.AutoSellClient;
import com.narek.autosell.config.AutoSellConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Parses the server's sell confirmation out of chat and keeps the running
 * totals. Purely observational - it reads chat, it never sends any.
 */
public final class ProfitTracker {
	private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");
	private static final DecimalFormat COUNT = new DecimalFormat("#,##0");
	private static final DateTimeFormatter STAMP =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private static final Path CSV =
			FabricLoader.getInstance().getConfigDir().resolve("autosell-log.csv");

	private static ProfitTracker instance;

	private Pattern compiled;
	private String compiledFrom;

	private double sessionMoney;
	private long sessionItems;
	private int sessionSales;

	private double runMoney;
	private long runItems;

	private double lastMoney;
	private boolean awaitingSale;
	private boolean saleConfirmed;

	public static ProfitTracker get() {
		if (instance == null) {
			instance = new ProfitTracker();
		}
		return instance;
	}

	/** Called just before the sell command goes out. */
	public void armForSale() {
		awaitingSale = true;
		saleConfirmed = false;
	}

	/** Consumed once by the routine; resets the flag. */
	public boolean consumeSaleConfirmed() {
		if (saleConfirmed) {
			saleConfirmed = false;
			return true;
		}
		return false;
	}

	public void beginRun() {
		runMoney = 0;
		runItems = 0;
	}

	private Pattern pattern() {
		AutoSellConfig cfg = AutoSellConfig.get();
		if (compiled == null || !cfg.sellRegex.equals(compiledFrom)) {
			try {
				compiled = Pattern.compile(cfg.sellRegex, Pattern.CASE_INSENSITIVE);
				compiledFrom = cfg.sellRegex;
			} catch (PatternSyntaxException e) {
				AutoSellClient.LOGGER.error("[autosell] invalid sellRegex", e);
				return null;
			}
		}
		return compiled;
	}

	/**
	 * Feeds an incoming chat line through the sell pattern.
	 *
	 * <p>The server's own formatting is stripped to plain text first, so the
	 * pattern does not have to cope with colour codes or the small-caps prefix.
	 */
	public void onGameMessage(Component message) {
		if (AutoSellClient.isEmittingOwnMessage()) {
			return; // never parse our own output
		}
		Pattern p = pattern();
		if (p == null) {
			return;
		}
		String plain = message.getString();
		Matcher m = p.matcher(plain);
		if (!m.find()) {
			return;
		}

		long items = parseLong(m.group(1));
		double money = parseDouble(m.groupCount() >= 2 ? m.group(2) : "0");

		sessionMoney += money;
		sessionItems += items;
		sessionSales++;
		runMoney += money;
		runItems += items;
		lastMoney = money;

		if (awaitingSale) {
			awaitingSale = false;
			saleConfirmed = true;
		}

		AutoSellClient.chat(Component.literal(
				"§a+$" + MONEY.format(money)
						+ " §7(" + COUNT.format(items) + " items) §8| §fsession: §a$"
						+ MONEY.format(sessionMoney)));

		if (AutoSellConfig.get().csvLogEnabled) {
			appendCsv(items, money);
		}
	}

	private static long parseLong(String raw) {
		try {
			return Long.parseLong(raw.replace(",", "").trim());
		} catch (NumberFormatException e) {
			return 0L;
		}
	}

	private static double parseDouble(String raw) {
		try {
			return Double.parseDouble(raw.replace(",", "").trim());
		} catch (NumberFormatException e) {
			return 0D;
		}
	}

	private void appendCsv(long items, double money) {
		try {
			boolean fresh = !Files.exists(CSV);
			StringBuilder sb = new StringBuilder();
			if (fresh) {
				sb.append("timestamp,items,money,session_items,session_money\n");
			}
			sb.append(LocalDateTime.now().format(STAMP)).append(',')
					.append(items).append(',')
					.append(money).append(',')
					.append(sessionItems).append(',')
					.append(sessionMoney).append('\n');

			Files.createDirectories(CSV.getParent());
			Files.writeString(CSV, sb.toString(), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			AutoSellClient.LOGGER.error("[autosell] could not append CSV log", e);
		}
	}

	public void resetSession() {
		sessionMoney = 0;
		sessionItems = 0;
		sessionSales = 0;
		runMoney = 0;
		runItems = 0;
		lastMoney = 0;
	}

	public String formatSessionTotal() {
		return "$" + MONEY.format(sessionMoney);
	}

	public String formatRunTotal() {
		return "$" + MONEY.format(runMoney);
	}

	public String formatLast() {
		return "$" + MONEY.format(lastMoney);
	}

	public double sessionMoney() {
		return sessionMoney;
	}

	public long sessionItems() {
		return sessionItems;
	}

	public int sessionSales() {
		return sessionSales;
	}

	public String formatSessionItems() {
		return COUNT.format(sessionItems);
	}

	public Path csvPath() {
		return CSV;
	}
}
