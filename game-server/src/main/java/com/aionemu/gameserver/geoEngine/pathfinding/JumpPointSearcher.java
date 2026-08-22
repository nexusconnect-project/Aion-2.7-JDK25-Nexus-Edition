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

import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.hash.TIntByteHashMap;
import gnu.trove.map.hash.TIntIntHashMap;

/**
 * Jump Point Search on a uniform-cost {@link NavigationGrid}.
 * <p>
 * JPS is algorithmically A* with an octile heuristic, but instead of expanding every
 * neighbour it "jumps" in straight lines, which is dramatically faster on open terrain
 * and corridors. Diagonal movement applies corner-cutting and climbing rules so paths
 * never clip through walls or unclimbable slopes.
 *
 * @author aion-lightning
 */
final class JumpPointSearcher {

	private static final int[][] DIRS = { { 0, -1 }, { 1, -1 }, { 1, 0 }, { 1, 1 }, { 0, 1 }, { -1, 1 }, { -1, 0 },
		{ -1, -1 } };

	private static final int STRAIGHT_COST = 10;
	private static final int DIAGONAL_COST = 14;

	private final NavigationGrid grid;
	private final int width;
	private final float maxClimb;
	private final int maxNodes;
	private final int goalX;
	private final int goalY;

	private final TIntIntHashMap gScore = new TIntIntHashMap(64, 0.75f, -1, -1);
	private final TIntIntHashMap cameFrom = new TIntIntHashMap(64, 0.75f, -1, -1);
	private final TIntByteHashMap closed = new TIntByteHashMap();
	private final IntMinHeap open = new IntMinHeap();

	private JumpPointSearcher(NavigationGrid grid, int goalX, int goalY, float maxClimb, int maxNodes) {
		this.grid = grid;
		this.width = grid.getWidth();
		this.goalX = goalX;
		this.goalY = goalY;
		this.maxClimb = maxClimb;
		this.maxNodes = maxNodes;
	}

	/**
	 * Finds a smoothed path between two world coordinates.
	 *
	 * @return cell indices (packed y*width+x) of the path, or null if unreachable
	 */
	static int[] findPath(NavigationGrid grid, float sx, float sy, float ex, float ey, float maxClimb, int maxNodes) {
		int start = grid.nearestWalkable(grid.worldToCellX(sx), grid.worldToCellY(sy), 8);
		int goal = grid.nearestWalkable(grid.worldToCellX(ex), grid.worldToCellY(ey), 8);
		if (start == -1 || goal == -1) {
			return null;
		}
		if (start == goal) {
			return new int[] { start };
		}
		int goalCellX = goal % grid.getWidth();
		int goalCellY = goal / grid.getWidth();
		JumpPointSearcher searcher = new JumpPointSearcher(grid, goalCellX, goalCellY, maxClimb, maxNodes);
		int[] cells = searcher.search(start);
		if (cells == null) {
			return null;
		}
		return grid.smooth(cells, maxClimb);
	}

	private int[] search(int start) {
		open.add(pack(start, heuristic(start % width, start / width)));
		gScore.put(start, 0);
		cameFrom.put(start, -1);
		int expansions = 0;
		while (!open.isEmpty()) {
			long entry = open.pop();
			int node = (int) (entry & 0xFFFFFFFFL);
			if (closed.containsKey(node)) {
				continue;
			}
			if (node == goalY * width + goalX) {
				return reconstruct(node);
			}
			if (++expansions > maxNodes) {
				return null;
			}
			closed.put(node, (byte) 1);
			expand(node);
		}
		return null;
	}

	private void expand(int node) {
		int x = node % width;
		int y = node / width;
		boolean isStart = !cameFrom.containsKey(node) || cameFrom.get(node) == -1;
		int pdx = 0;
		int pdy = 0;
		if (!isStart) {
			int p = cameFrom.get(node);
			pdx = Integer.signum(x - (p % width));
			pdy = Integer.signum(y - (p / width));
		}
		int gNode = gScore.get(node);
		for (int i = 0; i < DIRS.length; i++) {
			int dx = DIRS[i][0];
			int dy = DIRS[i][1];
			if (!isStart) {
				if (!isNatural(dx, dy, pdx, pdy) && !hasForcedNeighbor(x, y, dx, dy)) {
					continue;
				}
			}
			int jp = jump(x, y, dx, dy);
			if (jp == -1) {
				continue;
			}
			int jx = jp % width;
			int jy = jp / width;
			int stepCount = Math.max(Math.abs(jx - x), Math.abs(jy - y));
			int stepCost = (dx != 0 && dy != 0) ? DIAGONAL_COST : STRAIGHT_COST;
			int newG = gNode + stepCount * stepCost;
			if (!gScore.containsKey(jp) || newG < gScore.get(jp)) {
				gScore.put(jp, newG);
				cameFrom.put(jp, node);
				open.add(pack(jp, newG + heuristic(jx, jy)));
			}
		}
	}

	/**
	 * Walks in a straight line from (x, y) in direction (dx, dy) until a jump point or the
	 * goal is found. Returns -1 when the line is blocked.
	 */
	private int jump(int x, int y, int dx, int dy) {
		int cx = x;
		int cy = y;
		while (true) {
			int nx = cx + dx;
			int ny = cy + dy;
			if (!canStep(cx, cy, nx, ny)) {
				return -1;
			}
			cx = nx;
			cy = ny;
			if (cx == goalX && cy == goalY) {
				return cy * width + cx;
			}
			if (hasForcedNeighbor(cx, cy, dx, dy)) {
				return cy * width + cx;
			}
			if (dx != 0 && dy != 0 && (jump(cx, cy, dx, 0) != -1 || jump(cx, cy, 0, dy) != -1)) {
				return cy * width + cx;
			}
		}
	}

	/**
	 * A step is allowed when both cells are walkable, the height difference is within
	 * maxClimb and (for diagonal moves) the corner cells are not cut.
	 */
	private boolean canStep(int x, int y, int nx, int ny) {
		if (x == nx || y == ny) {
			return grid.canTraverse(x, y, nx, ny, maxClimb);
		}
		return grid.canTraverse(x, y, nx, y, maxClimb) && grid.canTraverse(x, y, x, ny, maxClimb)
			&& grid.canTraverse(x, y, nx, ny, maxClimb);
	}

	private boolean hasForcedNeighbor(int x, int y, int dx, int dy) {
		if (dx != 0) {
			for (int s = -1; s <= 1; s += 2) {
				if (!grid.isWalkable(x, y + s) && grid.isWalkable(x + dx, y + s)) {
					return true;
				}
			}
		}
		if (dy != 0) {
			for (int s = -1; s <= 1; s += 2) {
				if (!grid.isWalkable(x + s, y) && grid.isWalkable(x + s, y + dy)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * @return true when the direction (dx, dy) is a natural continuation of the incoming
	 *         direction (pdx, pdy)
	 */
	private boolean isNatural(int dx, int dy, int pdx, int pdy) {
		if (pdx != 0 && pdy == 0) {
			return dx == pdx && dy == 0;
		}
		if (pdx == 0 && pdy != 0) {
			return dx == 0 && dy == pdy;
		}
		boolean sameDiagonal = dx == pdx && dy == pdy;
		boolean horizontalComponent = dy == 0 && dx == pdx;
		boolean verticalComponent = dx == 0 && dy == pdy;
		return sameDiagonal || horizontalComponent || verticalComponent;
	}

	private int heuristic(int x, int y) {
		int dx = Math.abs(goalX - x);
		int dy = Math.abs(goalY - y);
		return STRAIGHT_COST * Math.max(dx, dy) + (DIAGONAL_COST - STRAIGHT_COST) * Math.min(dx, dy);
	}

	private static long pack(int node, int f) {
		return ((long) f << 32) | (node & 0xFFFFFFFFL);
	}

	private int[] reconstruct(int goal) {
		TIntArrayList path = new TIntArrayList(32);
		int cur = goal;
		while (true) {
			path.add(cur);
			int p = cameFrom.get(cur);
			if (p == -1) {
				break;
			}
			cur = p;
		}
		int n = path.size();
		int[] out = new int[n];
		for (int i = 0; i < n; i++) {
			out[i] = path.get(n - 1 - i);
		}
		return out;
	}
}
