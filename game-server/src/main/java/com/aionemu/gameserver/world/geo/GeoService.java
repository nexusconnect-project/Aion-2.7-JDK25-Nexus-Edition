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
package com.aionemu.gameserver.world.geo;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.geoEngine.math.Vector3f;

import com.aionemu.gameserver.configs.main.GeoDataConfig;
import com.aionemu.gameserver.geoEngine.models.GeoMap;
import com.aionemu.gameserver.geoEngine.pathfinding.PathfindingService;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.VisibleObject;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.utils.MathUtil;

/**
 * @author ATracer
 */
public class GeoService {

	private static final Logger log = LoggerFactory.getLogger(GeoService.class);
	private GeoData geoData;

	/**
	 * Initialize geodata based on configuration, load necessary structures
	 */
	public void initializeGeo() {
		switch (getConfiguredGeoType()) {
			case GEO_MESHES:
				geoData = new RealGeoData();
				break;
			case NO_GEO:
				geoData = new DummyGeoData();
				break;
		}
		log.info("Configured Geo type: " + getConfiguredGeoType());
		geoData.loadGeoMaps();
		if (GeoDataConfig.GEO_PATHFINDING_ENABLE) {
			warmUpPathfinding(geoData.getLoadedMaps());
		}
	}

	/**
	 * Pre-builds navigation grids on a daemon thread so the game thread never stalls on a
	 * multi-second grid build when a path is requested for the first time.
	 */
	private void warmUpPathfinding(final List<GeoMap> maps) {
		if (maps == null || maps.isEmpty()) {
			return;
		}
		Thread thread = new Thread(new Runnable() {

			@Override
			public void run() {
				PathfindingService.getInstance().warmUp(maps);
			}
		}, "PathfindingGridWarmup");
		thread.setDaemon(true);
		thread.start();
	}

	public void setDoorState(int worldId, int instanceId, String name, boolean state){
		if (GeoDataConfig.GEO_ENABLE) {
			geoData.getMap(worldId).setDoorState(instanceId, name, state);
		}
	}

	/**
	 * @param object
	 * @return
	 */
	public float getZAfterMoveBehind(int worldId, float x, float y, float z, int instanceId) {
		if (GeoDataConfig.GEO_ENABLE) {
			return getZ(worldId, x, y, z, 0, instanceId);
		}
		return getZ(worldId, x, y, z, 0.5f, instanceId);
	}

	/**
	 * @param object
	 * @return
	 */
	public float getZ(VisibleObject object) {
		return geoData.getMap(object.getWorldId()).getZ(object.getX(), object.getY(), object.getZ(), object.getInstanceId());
	}

	/**
	 * @param worldId
	 * @param x
	 * @param y
	 * @param z
	 * @param defaultUp
	 * @return
	 */
	public float getZ(int worldId, float x, float y, float z, float defaultUp, int instanceId) {
		float newZ = geoData.getMap(worldId).getZ(x, y, z, instanceId);
		if (!GeoDataConfig.GEO_ENABLE) {
			newZ += defaultUp;
		}
		else {
			newZ += 0.5f;
		}
		return newZ;
	}

	/**
	 * @param worldId
	 * @param x
	 * @param y
	 * @return
	 */
	public float getZ(int worldId, float x, float y) {
		return geoData.getMap(worldId).getZ(x, y);
	}

	/**
	 * @param object
	 * @param target
	 * @return
	 */
	public boolean canSee(VisibleObject object, VisibleObject target) {
		if(!GeoDataConfig.CANSEE_ENABLE){
			return true;
		}
		float limit = (float) (MathUtil.getDistance(object, target)- target.getObjectTemplate().getBoundRadius().getCollision());
		if (limit <= 0)
			return true;
		return geoData.getMap(object.getWorldId()).canSee(object.getX(), object.getY(), object.getZ()+object.getObjectTemplate().getBoundRadius().getUpper()/2, target.getX(),
			target.getY(), target.getZ()+target.getObjectTemplate().getBoundRadius().getUpper()/2, limit, object.getInstanceId());
	}
	
	public boolean canSee(int worldId, float x, float y, float z, float x1, float y1, float z1, float limit, int instanceId){
		return geoData.getMap(worldId).canSee(x, y, z, x1, y1, z1, limit, instanceId);
	}

	/**
	 * Eleanor (aionsdo) straight-line passability check between two creatures. Used by the
	 * movement steering to decide whether a mob can move directly towards its target or must
	 * fan-scan for an alternate step. Heights follow aionsdo: source {@code upper/2} (capped
	 * 2.2), target {@code max(0.6, upper*0.7)} (1.5 for players).
	 */
	public boolean canPass(Creature object, Creature target) {
		float limit = (float) (MathUtil.getDistance(object, target) - target.getObjectTemplate().getBoundRadius().getCollision());
		if (limit <= 0)
			return true;
		float upperTarget = Math.min(target.getObjectTemplate().getBoundRadius().getUpper() / 2f, 2.2f);
		float objectUp = object.getObjectTemplate().getBoundRadius().getUpper() / 2f;
		if (object instanceof Player)
			objectUp = 1.5f;
		else if (target instanceof Player)
			upperTarget = 1.5f;
		return geoData.getMap(object.getWorldId()).canPass(object.getX(), object.getY(),
			object.getZ() + objectUp, target.getX(), target.getY(),
			target.getZ() + upperTarget, limit, object.getInstanceId());
	}

	public boolean canPass(int worldId, float x, float y, float z, float x1, float y1, float z1, float limit, int instanceId) {
		return geoData.getMap(worldId).canPass(x, y, z, x1, y1, z1, limit, instanceId);
	}
	public GeoMap getMap(int worldId) {
		return geoData.getMap(worldId);
	}

	public boolean isGeoOn() {
		return GeoDataConfig.GEO_ENABLE;
	}
	
	public Vector3f getClosestCollision(Creature object, float x, float y, float z, boolean changeDirction) {
		return geoData.getMap(object.getWorldId())
			.getClosestCollision(object.getX(), object.getY(), object.getZ(), x, y, z, changeDirction, object.isInFlyingState(), object.getInstanceId());
	}

	public boolean isCollisionMesh(Creature object, float x, float y, float z, float targetX, float targetY, float targetZ) {
		return geoData.getMap(object.getWorldId()).isCollisionMesh(x, y, z, targetX, targetY, targetZ, object.getInstanceId());
	}

	/**
	 * Tests whether a horizontal step from the creature's position towards the target is
	 * blocked by solid geometry at chest AND head height. The heights are derived from the
	 * ground the creature actually stands on ({@link GeoMap#getZ}), so they always match
	 * the navigation grid's terrain-based band (terrain + {@link GeoDataConfig#GEO_PATHFINDING_MAX_BLOCK}
	 * and + 0.8 higher). Slopes and stairs (heightfield) are never meshes, so they never
	 * block; walls, rocks, tree trunks and low canopies do. Keeping grid and per-step
	 * checks on identical heights prevents both walking through walls and the mob freezing
	 * at an obstacle that the grid routes through but the step check rejects.
	 */
	public boolean isSolidStep(Creature object, float targetX, float targetY) {
		GeoMap map = geoData.getMap(object.getWorldId());
		int iid = object.getInstanceId();
		float surfaceZ = map.getZ(object.getX(), object.getY(), object.getZ() + 2f, iid);
		float chest = surfaceZ + GeoDataConfig.GEO_PATHFINDING_MAX_BLOCK;
		float head = chest + 0.8f;
		return map.isCollisionMesh(object.getX(), object.getY(), chest, targetX, targetY, chest, iid)
			|| map.isCollisionMesh(object.getX(), object.getY(), head, targetX, targetY, head, iid);
	}

	public GeoType getConfiguredGeoType() {
		if (GeoDataConfig.GEO_ENABLE) {
			return GeoType.GEO_MESHES;
		}
		return GeoType.NO_GEO;
	}

	public static final GeoService getInstance() {
		return SingletonHolder.instance;
	}

	@SuppressWarnings("synthetic-access")
	private static final class SingletonHolder {

		protected static final GeoService instance = new GeoService();
	}
}
