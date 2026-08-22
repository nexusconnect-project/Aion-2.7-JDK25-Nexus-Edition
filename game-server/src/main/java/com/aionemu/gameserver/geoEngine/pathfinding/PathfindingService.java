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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.configs.main.GeoDataConfig;
import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.geoEngine.models.GeoMap;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.utils.MathUtil;
import com.aionemu.gameserver.world.geo.GeoService;

/**
 * Asynchronous pathfinding service (singleton).
 * <p>
 * Path requests from the game thread are answered from three layers, cheapest first:
 * <ol>
 * <li><b>straight line</b> - one collision ray; if the target is reachable in a direct
 * line no pathfinding is performed at all (covers most chase cases);</li>
 * <li><b>LRU path cache</b> - reuses a recently computed path for the same start/end;</li>
 * <li><b>JPS on the navigation grid</b> - computed on a bounded worker pool, the game
 * thread never blocks. Navigation grids are built lazily per world on first use.</li>
 * </ol>
 * <p>
 * Worker threads are daemons, so a server shutdown never hangs on the pool.
 *
 * @author aion-lightning
 */
public final class PathfindingService {

	private static final Logger log = LoggerFactory.getLogger(PathfindingService.class);

	/** how close the cached path's end must be to the requested goal to be reused */
	static final float CACHE_GOAL_TOLERANCE = 6f;

	private final ExecutorService executor;
	private final PathCache cache = new PathCache(GeoDataConfig.GEO_PATHFINDING_CACHE_SIZE);
	private final ConcurrentHashMap<Integer, NavigationGrid> grids = new ConcurrentHashMap<Integer, NavigationGrid>();

	private PathfindingService() {
		int threads = Math.max(1, GeoDataConfig.GEO_PATHFINDING_THREADS);
		executor = Executors.newFixedThreadPool(threads, new ThreadFactory() {

			private final AtomicInteger counter = new AtomicInteger();

			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r, "PathfindingWorker-" + counter.incrementAndGet());
				t.setDaemon(true);
				return t;
			}
		});
		log.info("Pathfinding service started with " + threads + " worker thread(s)");
	}

	/**
	 * Requests a path from the NPC position to the target. Never blocks the caller.
	 *
	 * @return a ready or pending {@link PathRequest}, or null when a straight line is fine
	 *         (no pathfinding needed) or the owner is flying
	 */
	public PathRequest requestPath(Npc npc, float tx, float ty, float tz) {
		if (npc.isInFlyingState()) {
			return null;
		}
		float sx = npc.getX();
		float sy = npc.getY();
		float sz = npc.getZ();

		if (isStraightLineClear(npc, sx, sy, sz, tx, ty, tz)) {
			return null;
		}

		NavigationGrid grid = grids.get(npc.getWorldId());
		if (grid != null) {
			int csx = grid.worldToCellX(sx);
			int csy = grid.worldToCellY(sy);
			int cex = grid.worldToCellX(tx);
			int cey = grid.worldToCellY(ty);
			PathResult cached = cache.get(npc.getWorldId(), csx, csy, cex, cey);
			if (cached != null) {
				PathRequest req = new PathRequest();
				req.done = true;
				req.result = cached;
				return req;
			}
		}

		PathRequest req = new PathRequest();
		req.worldId = npc.getWorldId();
		req.instanceId = npc.getInstanceId();
		req.startX = sx;
		req.startY = sy;
		req.startZ = sz;
		req.targetX = tx;
		req.targetY = ty;
		req.targetZ = tz;
		executor.execute(req);
		return req;
	}

	private boolean isStraightLineClear(Npc npc, float sx, float sy, float sz, float tx, float ty, float tz) {
		Vector3f reach = GeoService.getInstance().getClosestCollision(npc, tx, ty, tz, true);
		float dist = (float) MathUtil.getDistance(sx, sy, sz, tx, ty, tz);
		float reachDist = (float) MathUtil.getDistance(sx, sy, sz, reach.x, reach.y, reach.z);
		return dist - reachDist < 1.5f;
	}

	/**
	 * Gets the navigation grid for a world, building it lazily on first use.
	 * The build may take a while, so it runs outside any lock; concurrent builds of the
	 * same world are harmless (last one wins).
	 */
	NavigationGrid getOrCreateGrid(int worldId) {
		NavigationGrid grid = grids.get(worldId);
		if (grid != null) {
			return grid;
		}
		GeoMap map = GeoService.getInstance().getMap(worldId);
		if (map == null || map.getTerrainData() == null) {
			return null;
		}
		grid = NavigationGrid.build(worldId, map);
		if (grid != null) {
			grids.put(worldId, grid);
			log.info("Navigation grid for world " + worldId + " cached");
		}
		return grid;
	}

	/**
	 * Pre-builds navigation grids for all loaded maps so the first path request never
	 * stalls a worker on a multi-second build. Runs on the caller's thread (a daemon
	 * warm-up thread started at server boot); lazy requests may still build missing
	 * worlds concurrently, which is harmless. World builds are parallelized on a
	 * dedicated pool so the whole set finishes in a few minutes instead of serially.
	 */
	public void warmUp(List<GeoMap> maps) {
		if (maps == null || maps.isEmpty()) {
			return;
		}
		List<GeoMap> targets = new ArrayList<GeoMap>();
		for (GeoMap map : maps) {
			if (map == null || map.getTerrainData() == null) {
				continue;
			}
			int worldId = map.getWorldId();
			if (worldId > 0 && !grids.containsKey(worldId)) {
				targets.add(map);
			}
		}
		if (targets.isEmpty()) {
			return;
		}
		int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
		ExecutorService pool = Executors.newFixedThreadPool(threads, new ThreadFactory() {

			private final AtomicInteger counter = new AtomicInteger();

			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r, "PathfindingGridBuild-" + counter.incrementAndGet());
				t.setDaemon(true);
				return t;
			}
		});
		long start = System.currentTimeMillis();
		final AtomicInteger built = new AtomicInteger();
		final CountDownLatch latch = new CountDownLatch(targets.size());
		try {
			for (final GeoMap map : targets) {
				pool.execute(new Runnable() {

					@Override
					public void run() {
						try {
							if (getOrCreateGrid(map.getWorldId()) != null) {
								built.incrementAndGet();
							}
						}
						finally {
							latch.countDown();
						}
					}
				});
			}
			latch.await();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		finally {
			pool.shutdownNow();
		}
		log.info("Pathfinding warm-up finished in " + (System.currentTimeMillis() - start) + " ms, " + built.get()
			+ " grid(s) pre-built");
	}

	/**
	 * Stores a computed path in the LRU cache. Called from worker threads only.
	 */
	void cachePath(int worldId, float sx, float sy, float ex, float ey, NavigationGrid grid, PathResult path) {
		cache.put(worldId, grid.worldToCellX(sx), grid.worldToCellY(sy), grid.worldToCellX(ex), grid.worldToCellY(ey), path);
	}

	public void shutdown() {
		executor.shutdownNow();
	}

	public static PathfindingService getInstance() {
		return SingletonHolder.instance;
	}

	private static final class SingletonHolder {

		private static final PathfindingService instance = new PathfindingService();
	}
}
