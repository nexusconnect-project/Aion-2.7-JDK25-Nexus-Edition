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

/**
 * Immutable result of a pathfinding request: an ordered list of world-space waypoints
 * (flat float arrays, no per-waypoint allocations).
 *
 * @author aion-lightning
 */
public final class PathResult {

	public final float[] xs;
	public final float[] ys;
	public final float[] zs;
	public final int count;
	public final long createdAt = System.currentTimeMillis();

	public PathResult(float[] xs, float[] ys, float[] zs) {
		this.xs = xs;
		this.ys = ys;
		this.zs = zs;
		this.count = xs.length;
	}

	@Override
	public String toString() {
		return "PathResult[" + count + " waypoints]";
	}
}
