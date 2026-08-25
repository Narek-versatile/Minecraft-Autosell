package com.narek.autosell.core;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Folds decorated server text down to plain ASCII for matching.
 *
 * <p>PlanetPVP writes its UI in Unicode small capitals - "MOBS" is really
 * {@code ᴍᴏʙꜱ}. Those are distinct codepoints, not styled
 * ASCII, so a naive uppercase-and-strip reduces them to nothing and every name
 * comparison silently fails.
 */
public final class TextFold {

	private TextFold() {
	}

	/**
	 * Small-capital and related codepoints that {@link Normalizer} does not
	 * decompose, mapped to their ASCII letter.
	 */
	private static char foldChar(char c) {
		return switch (c) {
			case 'ᴀ' -> 'A';
			case 'ʙ' -> 'B';
			case 'ᴄ' -> 'C';
			case 'ᴅ' -> 'D';
			case 'ᴇ' -> 'E';
			case 'ꜰ' -> 'F';
			case 'ɢ' -> 'G';
			case 'ʜ' -> 'H';
			case 'ɪ' -> 'I';
			case 'ᴊ' -> 'J';
			case 'ᴋ' -> 'K';
			case 'ʟ' -> 'L';
			case 'ᴍ' -> 'M';
			case 'ɴ' -> 'N';
			case 'ᴏ' -> 'O';
			case 'ᴘ' -> 'P';
			case 'ǫ', 'ꞯ' -> 'Q';
			case 'ʀ' -> 'R';
			case 'ꜱ' -> 'S';
			case 'ᴛ' -> 'T';
			case 'ᴜ' -> 'U';
			case 'ᴠ' -> 'V';
			case 'ᴡ' -> 'W';
			case 'ʏ' -> 'Y';
			case 'ᴢ' -> 'Z';
			default -> c;
		};
	}

	/**
	 * Plain uppercase ASCII: small caps folded, accents stripped, runs of
	 * anything else collapsed to single spaces.
	 */
	public static String normalize(String raw) {
		if (raw == null || raw.isEmpty()) {
			return "";
		}
		// NFKD splits accented and styled forms into base + combining marks.
		String decomposed = Normalizer.normalize(raw, Normalizer.Form.NFKD);

		StringBuilder sb = new StringBuilder(decomposed.length());
		for (int i = 0; i < decomposed.length(); i++) {
			char c = decomposed.charAt(i);
			if (Character.getType(c) == Character.NON_SPACING_MARK) {
				continue;
			}
			sb.append(foldChar(c));
		}

		return sb.toString()
				.toUpperCase(Locale.ROOT)
				.replaceAll("[^A-Z0-9 ]", " ")
				.replaceAll("\\s+", " ")
				.trim();
	}

	/** Debug helper: renders a string as escaped codepoints. */
	public static String codepoints(String raw) {
		if (raw == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		raw.codePoints().forEach(cp -> {
			if (cp >= 32 && cp < 127) {
				sb.append((char) cp);
			} else {
				sb.append(String.format("\\u%04X", cp));
			}
		});
		return sb.toString();
	}
}
