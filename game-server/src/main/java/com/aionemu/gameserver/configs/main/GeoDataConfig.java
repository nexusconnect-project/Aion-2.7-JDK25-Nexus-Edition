/*
 * This file is part of aion-lightning <aion-lightning.com>.
 *
 * aion-lightning is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * aion-lightning is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with aion-lightning. If not, see <http://www.gnu.org/licenses/>.
 */
package com.aionemu.gameserver.configs.main;

import com.aionemu.commons.configuration.Property;

public class GeoDataConfig {

	/**
	 * Geodata enable
	 */
	@Property(key = "gameserver.geodata.enable", defaultValue = "false")
	public static boolean GEO_ENABLE;
	
	/**
	 * Enable canSee checks using geodata.
	 */
	@Property(key = "gameserver.geodata.cansee.enable", defaultValue = "true")
	public static boolean CANSEE_ENABLE;
	
	/**
	 * Enable Fear skill using geodata.
	 */
	@Property(key = "gameserver.geodata.fear.enable", defaultValue = "true")
	public static boolean FEAR_ENABLE;

	/**
	 * Enable Geo checks during npc movement (prevent flying mobs)
	 */
	@Property(key = "gameserver.geo.npc.move", defaultValue = "false")
	public static boolean GEO_NPC_MOVE;

	/**
	 * Enable npc checks aggro target visibility range (canSee)
	 */
	@Property(key = "gameserver.geo.npc.aggro", defaultValue = "false")
	public static boolean GEO_NPC_AGGRO;
	
	/**
	 * Enable geo materials using skills
	 */
	@Property(key = "gameserver.geo.materials.enable", defaultValue = "false")
	public static boolean GEO_MATERIALS_ENABLE;

	/**
	 * Show collision zone name and skill id
	 */
	@Property(key = "gameserver.geo.materials.showdetails", defaultValue = "false")
	public static boolean GEO_MATERIALS_SHOWDETAILS;

	/**
	 * Enable geo shields
	 */
	@Property(key = "gameserver.geo.shields.enable", defaultValue = "false")
	public static boolean GEO_SHIELDS_ENABLE;
	
	/**
	 * Enable geo doors
	 */
	@Property(key = "gameserver.geo.doors.enable", defaultValue = "false")
	public static boolean GEO_DOORS_ENABLE;
	
	/**
	 * Object factory for geodata primitives enabled
	 */
	@Property(key = "gameserver.geodata.objectfactory.enabled", defaultValue = "true")
	public static boolean GEO_OBJECT_FACTORY_ENABLE;

	/**
	 * Enable pathfinding (JPS on pre-baked navigation grids + LRU path cache)
	 */
	@Property(key = "gameserver.geo.pathfinding.enable", defaultValue = "true")
	public static boolean GEO_PATHFINDING_ENABLE;

	/**
	 * Navigation grid resolution in world units per cell
	 */
	@Property(key = "gameserver.geo.pathfinding.resolution", defaultValue = "4")
	public static int GEO_PATHFINDING_RESOLUTION;

	/**
	 * Maximum number of JPS node expansions per search (latency guard)
	 */
	@Property(key = "gameserver.geo.pathfinding.max.nodes", defaultValue = "2048")
	public static int GEO_PATHFINDING_MAX_NODES;

	/**
	 * Number of worker threads computing paths
	 */
	@Property(key = "gameserver.geo.pathfinding.threads", defaultValue = "2")
	public static int GEO_PATHFINDING_THREADS;

	/**
	 * LRU path cache size (entries per world)
	 */
	@Property(key = "gameserver.geo.pathfinding.cache.size", defaultValue = "1024")
	public static int GEO_PATHFINDING_CACHE_SIZE;

	/**
	 * Maximum height difference in meters between two cells to be traversable on foot
	 */
	@Property(key = "gameserver.geo.pathfinding.max.climb", defaultValue = "1.5")
	public static float GEO_PATHFINDING_MAX_CLIMB;

	/**
	 * Height above the local terrain at which obstacles are detected (chest height check).
	 * Any wall/rock/building intersecting this band blocks the cell and the move step,
	 * while lower objects (steps, curbs, stairs) stay walkable
	 */
	@Property(key = "gameserver.geo.pathfinding.max.block", defaultValue = "0.8")
	public static float GEO_PATHFINDING_MAX_BLOCK;

	/**
	 * Terrain heights below this value are treated as void/blocked
	 */
	@Property(key = "gameserver.geo.pathfinding.min.height", defaultValue = "-5")
	public static float GEO_PATHFINDING_MIN_HEIGHT;
}
