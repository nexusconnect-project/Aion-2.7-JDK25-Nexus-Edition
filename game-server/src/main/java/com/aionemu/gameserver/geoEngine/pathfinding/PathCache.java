/*
 * This file is part of aion-lightning <aion-lightning.com>.
 *
 *  aion-lightning is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  aion-lightning is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with aion-lightning.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aionemu.gameserver.geoEngine.pathfinding;

import java.util.LinkedHashMap;
import java.util.Map;

import gnu.trove.map.hash.TIntObjectHashMap;

/**
 * Thread-safe LRU cache for recently computed paths. Keyed per world by the quantized
 * start/end cells, so chase requests that repeat between ticks hit the cache (typically
 * 70-90% hit rate for pursuing mobs).
 *
 * @author aion-lightning
 */
final class PathCache {

	private final int maxSize;
	private final TIntObjectHashMap<Map<Long, PathResult>> cache = new TIntObjectHashMap<Map<Long, PathResult>>();

	PathCache(int maxSize) {
		this.maxSize = Math.max(16, maxSize);
	}

	synchronized PathResult get(int worldId, int sx, int sy, int ex, int ey) {
		Map<Long, PathResult> paths = cache.get(worldId);
		if (paths == null) {
			return null;
		}
		// the key is quantized per navigation cell, so a hit is already "close enough"
		return paths.get(buildKey(sx, sy, ex, ey));
	}

	synchronized void put(int worldId, int sx, int sy, int ex, int ey, PathResult result) {
		Map<Long, PathResult> paths = cache.get(worldId);
		if (paths == null) {
			paths = new LinkedHashMap<Long, PathResult>(64, 0.75f, true) {

				private static final long serialVersionUID = 1L;

				@Override
				protected boolean removeEldestEntry(Map.Entry<Long, PathResult> eldest) {
					return size() > PathCache.this.maxSize;
				}
			};
			cache.put(worldId, paths);
		}
		paths.put(buildKey(sx, sy, ex, ey), result);
	}

	synchronized void clear() {
		cache.clear();
	}

	private static long buildKey(int sx, int sy, int ex, int ey) {
		return ((long) sx & 0xFFFFL) << 48 | ((long) sy & 0xFFFFL) << 32 | ((long) ex & 0xFFFFL) << 16
			| ((long) ey & 0xFFFFL);
	}
}
