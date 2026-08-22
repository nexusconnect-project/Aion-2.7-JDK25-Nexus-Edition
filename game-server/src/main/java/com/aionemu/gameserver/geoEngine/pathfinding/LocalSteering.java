package com.aionemu.gameserver.geoEngine.pathfinding;

import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.world.geo.GeoService;

/**
 * Local steering ported from Eleanor (aionsdo) {@code PathfindHelper.selectStep}.
 * When an NPC's immediate step is blocked it fan-scans 16 directions within a +/-90deg
 * arc and returns the passable point closest to the target. All math uses primitives to
 * avoid allocations in the movement hot path.
 *
 * @see com.aionemu.gameserver.world.geo.GeoService#canPass(int, float, float, float, float, float, float, float, int)
 */
public final class LocalSteering {

	private static final int VISIBLE_ANGLE = 180;
	private static final int PATHFIND_ANGLE_STEP = 15; // 16 candidates (0..15)
	private static final float OFFSET = VISIBLE_ANGLE / 2f; // 90
	private static final float Z_OFF_CAP = 2.2f;

	private LocalSteering() {
	}

	/**
	 * @param owner  moving creature
	 * @param goalX  target x
	 * @param goalY  target y
	 * @param goalZ  target z
	 * @param out    receives the chosen point (out[0]=x, out[1]=y, out[2]=z)
	 * @return true if a passable point was found, false if no ray passed
	 */
	public static boolean selectStep(Creature owner, float goalX, float goalY, float goalZ, float[] out) {
		GeoService gs = GeoService.getInstance();
		int world = owner.getWorldId();
		int iid = owner.getInstanceId();
		float upper = owner.getObjectTemplate().getBoundRadius().getUpper();

		float zOffset = Math.min(upper / 2f, Z_OFF_CAP); // source height
		float newZOffset = Math.max(0.6f, upper * 0.7f); // target height
		if (owner.getTarget() instanceof Player)
			newZOffset = 1.5f;

		float ox = owner.getX(), oy = owner.getY(), oz = owner.getZ();
		float baseDeg = (float) Math.toDegrees(Math.atan2(goalY - oy, goalX - ox));

		float future = (float) Math.hypot(goalX - ox, goalY - oy);
		if (future < 0.01f)
			return false;

		float minimalDistance = Short.MAX_VALUE;
		float bestX = 0, bestY = 0, bestZ = 0;
		boolean found = false;

		for (int i = 0; i < 16; i++) {
			double ang = Math.toRadians(baseDeg + (i * PATHFIND_ANGLE_STEP - OFFSET));
			float cx = ox + (float) Math.cos(ang) * future;
			float cy = oy + (float) Math.sin(ang) * future;
			float cz = gs.getZ(world, cx, cy, goalZ, 0, iid);
			if (cz == 0)
				continue;
			if (goalZ - cz > upper)
				continue;

			float dx = cx - ox, dy = cy - oy, dz = cz - oz;
			float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (!gs.canPass(world, ox, oy, oz + zOffset, cx, cy, cz + newZOffset, d, iid))
				continue;

			float canPassDistance = (float) Math.hypot(goalX - cx, goalY - cy);
			if (minimalDistance > canPassDistance) {
				minimalDistance = canPassDistance;
				bestX = cx;
				bestY = cy;
				bestZ = cz;
				found = true;
			}
			else {
				break;
			}
		}
		if (found) {
			out[0] = bestX;
			out[1] = bestY;
			out[2] = bestZ;
		}
		return found;
	}
}
