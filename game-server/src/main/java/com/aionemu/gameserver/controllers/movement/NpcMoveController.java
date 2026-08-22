/*
 * This file is part of aion_gates 
 *
 *  aion-emu is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  aiongates is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with aiongates.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aionemu.gameserver.controllers.movement;

import java.util.List;

import com.aionemu.gameserver.ai2.AI2Logger;
import com.aionemu.gameserver.ai2.AIState;
import com.aionemu.gameserver.ai2.AISubState;
import com.aionemu.gameserver.configs.main.GeoDataConfig;
import com.aionemu.gameserver.model.actions.NpcActions;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.VisibleObject;
import com.aionemu.gameserver.geoEngine.pathfinding.PathRequest;
import com.aionemu.gameserver.geoEngine.pathfinding.PathResult;
import com.aionemu.gameserver.geoEngine.pathfinding.PathfindingService;
import com.aionemu.gameserver.geoEngine.pathfinding.LocalSteering;
import com.aionemu.gameserver.model.templates.walker.RouteStep;
import com.aionemu.gameserver.model.templates.zone.Point2D;
import com.aionemu.gameserver.network.aion.serverpackets.SM_MOVE;
import com.aionemu.gameserver.spawnengine.WalkerGroup;
import com.aionemu.gameserver.taskmanager.tasks.MoveTaskManager;
import com.aionemu.gameserver.utils.MathUtil;
import com.aionemu.gameserver.utils.PacketSendUtility;
import com.aionemu.gameserver.world.World;
import com.aionemu.gameserver.world.geo.GeoService;

/**
 * @author ATracer
 */
public class NpcMoveController extends CreatureMoveController<Npc> {

	public static final float MOVE_CHECK_OFFSET = 0.1f;
	private static final float MOVE_OFFSET = 0.05f;

	// pathfinding related
	private static final float COLLISION_EPSILON = 0.4f;
	private static final float PATH_GOAL_REACH_DIST = 1.5f;
	private static final float WAYPOINT_REACH_DIST = 1.0f;
	private static final long PATH_REQUEST_COOLDOWN = 1500L;
	private static final long PATH_REFRESH_INTERVAL = 4000L;
	private static final float PATH_REFRESH_DIST = 5.0f;

	private Destination destination = Destination.TARGET_OBJECT;

	private float pointX;
	private float pointY;
	private float pointZ;

	private float offset = 0.1f;
	// walk related
	List<RouteStep> currentRoute;
	int currentPoint;
	int walkPause;

	private float cachedTargetZ;

	// pathfinding related
	private PathRequest activePathRequest;
	private PathResult path;
	private int pathIndex;
	private float pathGoalX;
	private float pathGoalY;
	private float pathGoalZ;
	private long pathLastRefresh;
	private long pathRequestCooldownUntil;

	// Eleanor (aionsdo) FollowMotor.TARGET_REVALIDATE_TIME = 200: re-check the straight
	// line to the target frequently so an opened path drops the stale A* route at once.
	private static final long FOLLOW_REVALIDATE_MS = 200L;
	private long lastFollowRevalidate;

	// reusable buffer for LocalSteering.selectStep (no per-tick allocation)
	private final float[] stepBuf = new float[3];

	public NpcMoveController(Npc owner) {
		super(owner);
	}

	private static enum Destination {
		TARGET_OBJECT,
		POINT;
	}

	/**
	 * Move to current target
	 */
	public void moveToTargetObject() {
		if (started.compareAndSet(false, true)) {
			if (owner.getAi2().isLogging()) {
				AI2Logger.moveinfo(owner, "MC: moveToTarget started");
			}
			destination = Destination.TARGET_OBJECT;
			updateLastMove();
			MoveTaskManager.getInstance().addCreature(owner);
		}
	}

	public void moveToPoint(float x, float y, float z) {
		if (started.compareAndSet(false, true)) {
			if (owner.getAi2().isLogging()) {
				AI2Logger.moveinfo(owner, "MC: moveToPoint started");
			}
			destination = Destination.POINT;
			pointX = x;
			pointY = y;
			pointZ = z;
			clearPath();
			updateLastMove();
			MoveTaskManager.getInstance().addCreature(owner);
		}
	}

	public void moveToNextPoint() {
		if (started.compareAndSet(false, true)) {
			if (owner.getAi2().isLogging()) {
				AI2Logger.moveinfo(owner, "MC: moveToNextPoint started");
			}
			destination = Destination.POINT;
			clearPath();
			updateLastMove();
			MoveTaskManager.getInstance().addCreature(owner);
		}
	}

	/**
	 * @return if destination reached
	 */
	@Override
	public void moveToDestination() {
		if (owner.getAi2().isLogging()) {
			AI2Logger.moveinfo(owner, "moveToDestination destination: " + destination);
		}

		if (NpcActions.isAlreadyDead(owner)) {
			abortMove();
			return;
		}

		if (!owner.canPerformMove() || (owner.getAi2().getSubState() == AISubState.CAST)) {
			if (owner.getAi2().isLogging()) {
				AI2Logger.moveinfo(owner, "moveToDestination can't perform move");
			}
			if (started.compareAndSet(true, false)) {
				setAndSendStopMove(owner);
			}
			updateLastMove();
			return;
		}
		else if (started.compareAndSet(false, true)) {
			movementMask = -32;
			PacketSendUtility.broadcastPacket(owner, new SM_MOVE(owner));
		}

		if (!started.get()) {
			if (owner.getAi2().isLogging()) {
				AI2Logger.moveinfo(owner, "moveToDestination not started");
			}
		}

		if (GeoDataConfig.GEO_PATHFINDING_ENABLE && GeoDataConfig.GEO_ENABLE) {
			try {
				float goalX;
				float goalY;
				float goalZ;
				if (destination == Destination.TARGET_OBJECT) {
					VisibleObject target = owner.getTarget();
					if (target == null) {
						return;
					}
					if (!(target instanceof Creature)) {
						return;
					}
					goalX = target.getX();
					goalY = target.getY();
					goalZ = getTargetZ((Npc) owner, (Creature) target);
				}
				else {
					goalX = pointX;
					goalY = pointY;
					goalZ = pointZ;
				}
				handlePathfinding(goalX, goalY, goalZ);
				if (path != null) {
					followPath(goalX, goalY, goalZ);
					updateLastMove();
					return;
				}
			}
			catch (Throwable t) {
				AI2Logger.moveinfo(owner, "pathfinding error, falling back to direct move: " + t);
				clearPath();
			}
		}

		switch (destination) {
			case TARGET_OBJECT:
				Npc npc = (Npc) owner;
				VisibleObject target = owner.getTarget();// todo no target
				if (target == null) {
					return;
				}
				if (!(target instanceof Creature)) {
					return;
				}
				if (MathUtil.getDistance(target, pointX, pointY, pointZ) > MOVE_CHECK_OFFSET) {
					Creature creature = (Creature) target;
					offset = npc.getController().getAttackDistanceToTarget();
					pointX = target.getX();
					pointY = target.getY();
					pointZ = getTargetZ(npc, creature);
				}
			case POINT:
				offset = 0.1f;
				moveToLocation(pointX, pointY, pointZ, offset);
				break;
		}
		updateLastMove();
	}

	/**
	 * Requests, adopts and refreshes a path to the current goal. While no path is active
	 * the NPC keeps moving straight; per-step collision prevents clipping into walls.
	 */
	private void handlePathfinding(float goalX, float goalY, float goalZ) {
		long now = System.currentTimeMillis();
		if (activePathRequest != null && activePathRequest.isDone()) {
			PathResult result = activePathRequest.getResult();
			activePathRequest = null;
			if (result != null && result.count > 0) {
				adoptPath(result, goalX, goalY, goalZ);
			}
			else {
				pathRequestCooldownUntil = now + PATH_REQUEST_COOLDOWN;
			}
		}
		if (path != null) {
			if (now - lastFollowRevalidate > FOLLOW_REVALIDATE_MS
				&& destination == Destination.TARGET_OBJECT) {
				lastFollowRevalidate = now;
				if (isStraightLineClear(goalX, goalY, goalZ)) {
					clearPath(); // opened path -> move straight, drop stale A* route
					return;
				}
			}
			if (now - pathLastRefresh > PATH_REFRESH_INTERVAL
				&& MathUtil.getDistance(goalX, goalY, goalZ, pathGoalX, pathGoalY, pathGoalZ) > PATH_REFRESH_DIST) {
				clearPath();
			}
			return;
		}
		if (activePathRequest != null) {
			return;
		}
		if (owner.isInFlyingState()) {
			return;
		}
		if (now < pathRequestCooldownUntil) {
			return;
		}
		if (isStraightLineClear(goalX, goalY, goalZ)) {
			return;
		}
		activePathRequest = PathfindingService.getInstance().requestPath(owner, goalX, goalY, goalZ);
		pathRequestCooldownUntil = now + PATH_REQUEST_COOLDOWN;
		if (activePathRequest != null && activePathRequest.isDone()) {
			PathResult result = activePathRequest.getResult();
			activePathRequest = null;
			if (result != null && result.count > 0) {
				adoptPath(result, goalX, goalY, goalZ);
			}
		}
	}

	/**
	 * Reuses the exact same check as the per-step collision ({@link GeoService#isSolidStep})
	 * so the LOS decision can never disagree with the movement collision, which would trap
	 * the NPC in a permanent "blocked -> re-request -> blocked" loop.
	 */
	private boolean isStraightLineClear(float tx, float ty, float tz) {
		return !GeoService.getInstance().isSolidStep(owner, tx, ty);
	}

	private void adoptPath(PathResult result, float goalX, float goalY, float goalZ) {
		this.path = result;
		this.pathIndex = 0;
		this.pathGoalX = goalX;
		this.pathGoalY = goalY;
		this.pathGoalZ = goalZ;
		this.pathLastRefresh = System.currentTimeMillis();
	}

	private void clearPath() {
		this.path = null;
		this.pathIndex = 0;
	}

	/**
	 * Moves the NPC along the current path towards the next waypoint. Clears the path when
	 * the goal or the final waypoint is reached.
	 */
	private void followPath(float goalX, float goalY, float goalZ) {
		if (path == null) {
			return;
		}
		if (MathUtil.getDistance(owner.getX(), owner.getY(), owner.getZ(), goalX, goalY, goalZ) < PATH_GOAL_REACH_DIST) {
			clearPath();
			return;
		}
		if (pathIndex >= path.count) {
			clearPath();
			return;
		}
		float wx = path.xs[pathIndex];
		float wy = path.ys[pathIndex];
		float wz = path.zs[pathIndex];
		if (MathUtil.getDistance(owner.getX(), owner.getY(), owner.getZ(), wx, wy, wz) < WAYPOINT_REACH_DIST) {
			pathIndex++;
			if (pathIndex >= path.count) {
				clearPath();
				return;
			}
			wx = path.xs[pathIndex];
			wy = path.ys[pathIndex];
			wz = path.zs[pathIndex];
		}
		moveToLocation(wx, wy, wz, 0.1f);
	}

	/**
	 * @param npc
	 * @param creature
	 * @return
	 */
	private float getTargetZ(Npc npc, Creature creature) {
		float targetZ = creature.getZ();
		if (GeoDataConfig.GEO_NPC_MOVE && creature.isInFlyingState() && !npc.isInFlyingState()) {
			if (npc.getGameStats().checkGeoNeedUpdate()) {
				cachedTargetZ = GeoService.getInstance().getZ(creature);
			}
			targetZ = cachedTargetZ;
		}
		return targetZ;
	}

	/**
	 * @param targetX
	 * @param targetY
	 * @param targetZ
	 * @param offset
	 * @return
	 */
	protected void moveToLocation(float targetX, float targetY, float targetZ, float offset) {
		boolean directionChanged = false;
		float ownerX = owner.getX();
		float ownerY = owner.getY();
		float ownerZ = owner.getZ();

		directionChanged = targetX != targetDestX || targetY != targetDestY || targetZ != targetDestZ;

		if (directionChanged) {
			heading = (byte) (Math.toDegrees(Math.atan2(targetY - ownerY, targetX - ownerX)) / 3);
		}

		if (owner.getAi2().isLogging()) {
			AI2Logger.moveinfo(owner, "OLD targetDestX: " + targetDestX + " targetDestY: " + targetDestY + " targetDestZ "
				+ targetDestZ);
		}

		targetDestX = targetX;
		targetDestY = targetY;
		targetDestZ = targetZ;

		if (owner.getAi2().isLogging()) {
			AI2Logger.moveinfo(owner, "ownerX=" + ownerX + " ownerY=" + ownerY + " ownerZ=" + ownerZ);
			AI2Logger.moveinfo(owner, "targetDestX: " + targetDestX + " targetDestY: " + targetDestY + " targetDestZ "
				+ targetDestZ);
		}

		float currentSpeed = owner.getGameStats().getMovementSpeedFloat();
		float futureDistPassed = currentSpeed * (System.currentTimeMillis() - lastMoveUpdate) / 1000f;

		float dist = (float) MathUtil.getDistance(ownerX, ownerY, ownerZ, targetX, targetY, targetZ);

		if (owner.getAi2().isLogging()) {
			AI2Logger.moveinfo(owner, "futureDist: " + futureDistPassed + " dist: " + dist);
		}

		if (dist == 0) {
			return;
		}

		if (futureDistPassed > dist) {
			futureDistPassed = dist;
		}

		float distFraction = futureDistPassed / dist;
		float newX = (targetDestX - ownerX) * distFraction + ownerX;
		float newY = (targetDestY - ownerY) * distFraction + ownerY;
		float newZ = (targetDestZ - ownerZ) * distFraction + ownerZ;
		if (GeoDataConfig.GEO_NPC_MOVE && GeoDataConfig.GEO_ENABLE && !owner.isInFlyingState()) {
			float stepDist = (float) MathUtil.getDistance(ownerX, ownerY, newX, newY);
			if (stepDist > COLLISION_EPSILON && GeoService.getInstance().isSolidStep(owner, newX, newY)) {
				// Eleanor (aionsdo) PathfindHelper.selectStep: pick the passable point
				// closest to the target among 16 fan-scanned directions instead of only
				// sliding along the X or Y axis.
				if (LocalSteering.selectStep(owner, targetX, targetY, targetZ, stepBuf)) {
					newX = stepBuf[0];
					newY = stepBuf[1];
					newZ = stepBuf[2];
					directionChanged = true;
				}
				else {
					clearPath();
					return;
				}
			}
		}
		if (GeoDataConfig.GEO_NPC_MOVE && GeoDataConfig.GEO_ENABLE && owner.getAi2().getSubState() != AISubState.WALK_PATH
			&& owner.getAi2().getState() != AIState.RETURNING
			&& owner.getGameStats().getLastGeoZUpdate() < System.currentTimeMillis()) {
			float geoZ = GeoService.getInstance().getZ(owner.getWorldId(), newX, newY, newZ, 0, owner.getInstanceId());
			if (Math.abs(newZ - geoZ) > 1)
				directionChanged = true;
			newZ = geoZ;
			owner.getGameStats().setLastGeoZUpdate(System.currentTimeMillis() + 1000);
		}
		if (owner.getAi2().isLogging()) {
			AI2Logger.moveinfo(owner, "newX=" + newX + " newY=" + newY + " newZ=" + newY);
		}

		World.getInstance().updatePosition(owner, newX, newY, newZ, heading, false);
		if (directionChanged) {
			movementMask = -32;
			PacketSendUtility.broadcastPacket(owner, new SM_MOVE(owner));
		}
	}

	@Override
	public void abortMove() {
		if (!started.get())
			return;
		resetMove();
		setAndSendStopMove(owner);
	}

	/**
	 * Initialize values to default ones
	 */
	public void resetMove() {
		if (owner.getAi2().isLogging()) {
			AI2Logger.moveinfo(owner, "MC perform stop");
		}
		started.set(false);
		targetDestX = 0;
		targetDestY = 0;
		targetDestZ = 0;
		pointX = 0;
		pointY = 0;
		pointZ = 0;
		clearPath();
	}

	/**
	 * Walker
	 * 
	 * @param currentRoute
	 */
	public void setCurrentRoute(List<RouteStep> currentRoute) {
		if (currentRoute == null)
			AI2Logger.info(owner.getAi2(), String.format("MC: setCurrentRoute is setting route to null (NPC id: {})!!!", owner.getNpcId()));
		this.currentRoute = currentRoute;
		this.currentPoint = 0;
	}

	public void setRouteStep(RouteStep step, RouteStep prevStep) {
		Point2D dest = null;
		if (owner.getWalkerGroup() != null) {
			dest = WalkerGroup.getLinePoint(new Point2D(prevStep.getX(), prevStep.getY()),
				new Point2D(step.getX(), step.getY()), owner.getWalkerGroupShift());
			this.pointZ = prevStep.getZ();
			if (GeoDataConfig.GEO_ENABLE && GeoDataConfig.GEO_NPC_MOVE) {
				// TODO: fix Z
			}
			owner.getWalkerGroup().setStep(owner, step.getRouteStep());
		}
		else {
			this.pointZ = step.getZ();
		}
		this.currentPoint = step.getRouteStep() - 1;
		this.pointX = dest == null ? step.getX() : dest.getX();
		this.pointY = dest == null ? step.getY() : dest.getY();
		this.destination = Destination.POINT;
		this.walkPause = step.getRestTime();
	}

	public int getCurrentPoint() {
		return currentPoint;
	}

	public boolean isReachedPoint() {
		return MathUtil.getDistance(owner.getX(), owner.getY(), owner.getZ(), pointX, pointY, pointZ) < MOVE_OFFSET;
	}

	public void chooseNextStep() {
		// Guard against a null route: some walker-group members enter WALKING / WALK_WAIT_GROUP
		// through WalkerGroup.setStep without going via startRouteWalking (which sets currentRoute).
		// Their next step is driven externally by WalkerGroup, so there is nothing to advance here.
		if (currentRoute == null) {
			if (owner.getAi2().isLogging()) {
				AI2Logger.moveinfo(owner, "MC: chooseNextStep skipped, currentRoute is null (group walker)");
			}
			return;
		}
		int oldPoint = currentPoint;
		if (currentPoint < (currentRoute.size() - 1)) {
			currentPoint++;
		}
		else {
			currentPoint = 0;
		}
		setRouteStep(currentRoute.get(currentPoint), currentRoute.get(oldPoint));
	}

	public int getWalkPause() {
		return walkPause;
	}
	
	public boolean isChangingDirection() {
		return currentPoint == 0;
	}

	@Override
	public final float getTargetX2() {
		return started.get() ? targetDestX : owner.getX();
	}

	@Override
	public final float getTargetY2() {
		return started.get() ? targetDestY : owner.getY();
	}

	@Override
	public final float getTargetZ2() {
		return started.get() ? targetDestZ : owner.getZ();
	}

	/**
	 * @return
	 */
	public boolean isFollowingTarget() {
		return destination == Destination.TARGET_OBJECT;
	}
}
