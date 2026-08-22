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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.configs.main.GeoDataConfig;

/**
 * A single pathfinding request. Submitted to the {@link PathfindingService} executor,
 * computed on a worker thread and polled by the game thread via {@link #isDone()} and
 * {@link #getResult()}. A {@code null} result means "no path found, keep moving straight".
 *
 * @author aion-lightning
 */
public final class PathRequest implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(PathRequest.class);

	// inputs
	int worldId;
	int instanceId;
	float startX;
	float startY;
	float startZ;
	float targetX;
	float targetY;
	float targetZ;

	// outputs (package-private, written by workers, read by the game thread)
	volatile PathResult result;
	volatile boolean done;

	@Override
	public void run() {
		try {
			NavigationGrid grid = PathfindingService.getInstance().getOrCreateGrid(worldId);
			if (grid == null) {
				return;
			}
			int[] cells = JumpPointSearcher.findPath(grid, startX, startY, targetX, targetY,
				GeoDataConfig.GEO_PATHFINDING_MAX_CLIMB, GeoDataConfig.GEO_PATHFINDING_MAX_NODES);
			if (cells == null || cells.length < 2) {
				return;
			}
			PathResult path = grid.buildPathResult(cells);
			if (path != null) {
				result = path;
				PathfindingService.getInstance().cachePath(worldId, startX, startY, targetX, targetY, grid, path);
			}
		}
		catch (Throwable t) {
			log.warn("Pathfinding error in world " + worldId + " from (" + startX + ", " + startY + ") to (" + targetX + ", "
				+ targetY + ")", t);
		}
		finally {
			done = true;
		}
	}

	public boolean isDone() {
		return done;
	}

	/**
	 * @return the computed path or null (still computing / no path found)
	 */
	public PathResult getResult() {
		return result;
	}
}
