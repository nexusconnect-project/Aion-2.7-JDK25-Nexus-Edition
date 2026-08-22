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

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.configs.main.GeoDataConfig;
import com.aionemu.gameserver.geoEngine.models.GeoMap;

/**
 * Pre-baked navigation grid for a single map.
 * <p>
 * The grid is derived from the map terrain heightmap (2 units per terrain cell). A cell is
 * considered blocked when a horizontal ray at chest height (terrain +
 * {@link GeoDataConfig#GEO_PATHFINDING_MAX_BLOCK} units) crosses the cell and hits a
 * geometry mesh (walls, rocks, buildings of any height), when the terrain is below
 * {@link GeoDataConfig#GEO_PATHFINDING_MIN_HEIGHT} or when the cell lies outside the world.
 * Slopes and low steps (below chest height) stay walkable, so stairs and hills are not
 * blocked. Each cell is swept on both axes, which also catches walls sitting on cell borders.
 * <p>
 * Movement between two cells is only allowed when the height difference is smaller than
 * {@link GeoDataConfig#GEO_PATHFINDING_MAX_CLIMB}.
 *
 * @author aion-lightning
 */
public final class NavigationGrid {

	private static final Logger log = LoggerFactory.getLogger(NavigationGrid.class);

	private final int worldId;
	private final int worldSize;
	private final int resolution; // world units per navigation cell
	private final int width; // navigation cells per axis
	private final short[] heights; // terrain height (raw /32f = world height)
	private final byte[] walkable; // 1 = walkable, 0 = blocked

	private NavigationGrid(int worldId, int worldSize, int resolution, short[] heights, byte[] walkable) {
		this.worldId = worldId;
		this.worldSize = worldSize;
		this.resolution = resolution;
		this.heights = heights;
		this.walkable = walkable;
		this.width = (int) Math.ceil(worldSize / (float) resolution);
	}

	public static NavigationGrid build(int worldId, GeoMap map) {
		short[] terrainData = map.getTerrainData();
		if (terrainData == null) {
			return null;
		}
		int worldSize = map.getWorldSize();
		if (worldSize <= 0) {
			return null;
		}
		int resolution = Math.max(1, GeoDataConfig.GEO_PATHFINDING_RESOLUTION);
		int gridSize = (int) Math.ceil(worldSize / (float) resolution);
		int cellCount = gridSize * gridSize;
		short[] heights = new short[cellCount];
		byte[] walkable = new byte[cellCount];

		int terrainSize = (int) Math.sqrt(terrainData.length);
		boolean flatMap = terrainData.length == 1;
		float minHeight = GeoDataConfig.GEO_PATHFINDING_MIN_HEIGHT;
		float chestRayHeight = GeoDataConfig.GEO_PATHFINDING_MAX_BLOCK;
		int blocked = 0;

		for (int cy = 0; cy < gridSize; cy++) {
			for (int cx = 0; cx < gridSize; cx++) {
				int index = cy * gridSize + cx;
				float worldX = cx * resolution + resolution / 2f;
				float worldY = cy * resolution + resolution / 2f;

				boolean outside = worldX >= worldSize || worldY >= worldSize;
				short rawHeight;
				float terrainHeight;
				if (flatMap) {
					rawHeight = terrainData[0];
					terrainHeight = rawHeight / 32f;
				}
				else {
					int tx = Math.min(terrainSize - 1, (int) (worldX / 2f));
					int ty = Math.min(terrainSize - 1, (int) (worldY / 2f));
					rawHeight = terrainData[ty + tx * terrainSize];
					terrainHeight = rawHeight / 32f;
				}
				heights[index] = rawHeight;

				if (outside || terrainHeight < minHeight) {
					walkable[index] = 0;
					blocked++;
					continue;
				}
				// horizontal sweeps across the cell on both axes at chest AND head height:
				// any wall/rock/building/tree trunk intersecting the walkable band blocks the
				// cell, while slopes, stairs and low steps stay free (terrain is a heightfield,
				// never a mesh). Sweeping slightly beyond the cell catches border walls.
				float cellMinX = cx * resolution - 0.5f;
				float cellMaxX = cx * resolution + resolution + 0.5f;
				float cellMinY = cy * resolution - 0.5f;
				float cellMaxY = cy * resolution + resolution + 0.5f;
				float midX = cx * resolution + resolution / 2f;
				float midY = cy * resolution + resolution / 2f;
				float chest = terrainHeight + chestRayHeight;
				float head = chest + 0.8f;
				if (map.isCollisionMesh(cellMinX, midY, chest, cellMaxX, midY, chest, 1)
					|| map.isCollisionMesh(midX, cellMinY, chest, midX, cellMaxY, chest, 1)
					|| map.isCollisionMesh(cellMinX, midY, head, cellMaxX, midY, head, 1)
					|| map.isCollisionMesh(midX, cellMinY, head, midX, cellMaxY, head, 1)) {
					walkable[index] = 0;
					blocked++;
				}
				else {
					walkable[index] = 1;
				}
			}
		}
		if (blocked > 0) {
			log.info("Path grid for world " + worldId + " built: " + cellCount + " cells, " + blocked + " blocked, resolution "
				+ resolution);
		}
		return new NavigationGrid(worldId, worldSize, resolution, heights, walkable);
	}

	public int getWorldId() {
		return worldId;
	}

	public int getResolution() {
		return resolution;
	}

	public int getWidth() {
		return width;
	}

	public boolean isInBounds(int x, int y) {
		return x >= 0 && y >= 0 && x < width && y < width;
	}

	public boolean isWalkable(int x, int y) {
		if (!isInBounds(x, y)) {
			return false;
		}
		return walkable[y * width + x] == 1;
	}

	/**
	 * @return true when both cells are walkable and the height difference is within maxClimb
	 */
	public boolean canTraverse(int x1, int y1, int x2, int y2, float maxClimb) {
		if (!isWalkable(x1, y1) || !isWalkable(x2, y2)) {
			return false;
		}
		return Math.abs(getHeight(x1, y1) - getHeight(x2, y2)) <= maxClimb;
	}

	public float getHeight(int x, int y) {
		return heights[y * width + x] / 32f;
	}

	/**
	 * Finds the nearest walkable cell within the given radius.
	 *
	 * @return packed cell index or -1
	 */
	public int nearestWalkable(int cx, int cy, int radius) {
		if (isWalkable(cx, cy)) {
			return cy * width + cx;
		}
		for (int r = 1; r <= radius; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dy = -r; dy <= r; dy++) {
					if (Math.max(Math.abs(dx), Math.abs(dy)) != r) {
						continue;
					}
					int nx = cx + dx;
					int ny = cy + dy;
					if (isWalkable(nx, ny)) {
						return ny * width + nx;
					}
				}
			}
		}
		return -1;
	}

	/**
	 * Cell center world coordinates.
	 */
	public float cellCenterX(int cx) {
		return cx * resolution + resolution / 2f;
	}

	public float cellCenterY(int cy) {
		return cy * resolution + resolution / 2f;
	}

	public int worldToCellX(float worldX) {
		return (int) (worldX / resolution);
	}

	public int worldToCellY(float worldY) {
		return (int) (worldY / resolution);
	}

	/**
	 * String-pulling smoothing of a cell path: removes intermediate cells when the direct
	 * segment is traversable on the grid.
	 */
	public int[] smooth(int[] cells, float maxClimb) {
		if (cells.length <= 2) {
			return cells;
		}
		List<Integer> result = new ArrayList<Integer>();
		result.add(cells[0]);
		int lastKept = 0;
		for (int i = 2; i < cells.length; i++) {
			if (!hasLineOfSight(cells[lastKept], cells[i], maxClimb)) {
				result.add(cells[i - 1]);
				lastKept = i - 1;
			}
		}
		if (lastKept != cells.length - 1) {
			result.add(cells[cells.length - 1]);
		}
		int[] out = new int[result.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = result.get(i);
		}
		return out;
	}

	/**
	 * @return true when the direct segment between two cells is fully traversable
	 */
	public boolean hasLineOfSight(int from, int to, float maxClimb) {
		int x0 = from % width;
		int y0 = from / width;
		int x1 = to % width;
		int y1 = to / width;
		int dx = Math.abs(x1 - x0);
		int dy = Math.abs(y1 - y0);
		int sx = x0 < x1 ? 1 : -1;
		int sy = y0 < y1 ? 1 : -1;
		int err = dx - dy;
		int px = x0;
		int py = y0;
		while (px != x1 || py != y1) {
			int nx = px;
			int ny = py;
			int e2 = 2 * err;
			if (e2 > -dy) {
				err -= dy;
				nx += sx;
			}
			if (e2 < dx) {
				err += dx;
				ny += sy;
			}
			if (nx == px && ny == py) {
				break; // safety
			}
			if (!canTraverse(px, py, nx, ny, maxClimb)) {
				return false;
			}
			px = nx;
			py = ny;
		}
		return true;
	}

	/**
	 * Converts a smoothed cell path to world-space waypoints with terrain heights.
	 */
	public PathResult buildPathResult(int[] cells) {
		int count = cells.length;
		float[] xs = new float[count];
		float[] ys = new float[count];
		float[] zs = new float[count];
		for (int i = 0; i < count; i++) {
			int cell = cells[i];
			int cx = cell % width;
			int cy = cell / width;
			xs[i] = cellCenterX(cx);
			ys[i] = cellCenterY(cy);
			zs[i] = getHeight(cx, cy);
		}
		return new PathResult(xs, ys, zs);
	}
}
