package com.narek.autosell.core;

import com.narek.autosell.config.AutoSellConfig;

/**
 * Detects N presses of the trigger key inside a rolling time window.
 *
 * <p>Fed from a mixin on {@code KeyboardHandler.keyPress} rather than a
 * {@code KeyMapping}, because the routine has to be triggerable while a
 * container GUI is open - and number keys are swallowed by container screens
 * for hotbar swapping, so a normal keybind would never see them there.
 */
public final class TriplePress {
	private static final int REQUIRED_PRESSES = 3;

	private final long[] presses = new long[REQUIRED_PRESSES];
	private int count;

	/**
	 * @return true when this press completes a triple-press inside the window
	 */
	public boolean press() {
		long now = System.currentTimeMillis();
		long window = AutoSellConfig.get().tripleWindowMs;

		// Drop presses that have aged out of the window.
		int kept = 0;
		for (int i = 0; i < count; i++) {
			if (now - presses[i] <= window) {
				presses[kept++] = presses[i];
			}
		}
		count = kept;

		if (count == REQUIRED_PRESSES) {
			// Window is full but stale entries were kept; shift left.
			System.arraycopy(presses, 1, presses, 0, REQUIRED_PRESSES - 1);
			count = REQUIRED_PRESSES - 1;
		}

		presses[count++] = now;

		if (count == REQUIRED_PRESSES && now - presses[0] <= window) {
			count = 0;
			return true;
		}
		return false;
	}

	public void reset() {
		count = 0;
	}
}
