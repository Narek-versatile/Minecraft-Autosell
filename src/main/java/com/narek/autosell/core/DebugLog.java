package com.narek.autosell.core;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Rolling diagnostic log of what the routines actually did.
 *
 * <p>Exists because failures that only appear on a later run are almost
 * impossible to describe from memory: by the time something looks wrong, the
 * state that caused it is gone. Every phase change, decision and abort is
 * written with a timestamp, so a stuck run can be read back afterwards instead
 * of reproduced.
 */
public final class DebugLog {

	private static final Path FILE =
			FabricLoader.getInstance().getConfigDir().resolve("autosell-debug.log");
	private static final Path OLD =
			FabricLoader.getInstance().getConfigDir().resolve("autosell-debug.log.1");

	private static final DateTimeFormatter STAMP =
			DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
	/** Rotate past this size so the file stays readable. */
	private static final long MAX_BYTES = 1_000_000;

	private static boolean enabled = true;

	private DebugLog() {
	}

	public static Path path() {
		return FILE;
	}

	public static boolean isEnabled() {
		return enabled;
	}

	public static boolean toggle() {
		enabled = !enabled;
		return enabled;
	}

	public static void log(String tag, String message) {
		if (!enabled) {
			return;
		}
		write("[" + LocalTime.now().format(STAMP) + "] " + tag + ": " + message + "\n");
	}

	/** Marks the start of a run, with a blank line so runs are easy to find. */
	public static void runStart(String what) {
		if (!enabled) {
			return;
		}
		write("\n===== " + what + " @ " + LocalTime.now().format(STAMP) + " =====\n");
	}

	private static void write(String text) {
		try {
			Files.createDirectories(FILE.getParent());
			if (Files.exists(FILE) && Files.size(FILE) > MAX_BYTES) {
				Files.move(FILE, OLD, StandardCopyOption.REPLACE_EXISTING);
			}
			Files.writeString(FILE, text, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			// Diagnostics must never take the game down with them.
			enabled = false;
		}
	}
}
