package de.piegames.blockmap.renderer;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

public class BlockState {

	Map<String, Map<String, Integer>> states;

	@SuppressWarnings("unused")
	private BlockState() {
		/* Used by GSON */
	}

	public BlockState(Map<String, Map<String, Integer>> states) {
		this.states = Objects.requireNonNull(states);
	}

	public int getProperty(String key, String value) {
		return states.getOrDefault(key, Collections.emptyMap()).get(value);
	}

	public int getSize() {
		return states.size();
	}
}
