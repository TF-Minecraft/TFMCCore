package net.tfminecraft.tfmccore.reference;

import java.util.Locale;

public class Station {
	public enum Click {
		RIGHT,
		SHIFT_RIGHT
	}

	private final String id;
	private final String block;
	private final Click click;

	public Station(String id, String block, Click click) {
		this.id = id;
		this.block = block;
		this.click = click != null ? click : Click.RIGHT;
	}

	public String getId() {
		return id;
	}

	public String getBlock() {
		return block;
	}

	public Click getClick() {
		return click;
	}

	public boolean matchesClick(boolean sneaking) {
		if (click == Click.SHIFT_RIGHT) {
			return sneaking;
		}
		return !sneaking;
	}

	public static Click parseClick(String raw) {
		if (raw == null || raw.isBlank()) {
			return Click.RIGHT;
		}
		String key = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
		if (key.equals("shift_right") || key.equals("shift") || key.equals("sneak_right")) {
			return Click.SHIFT_RIGHT;
		}
		return Click.RIGHT;
	}
}
