package com.aionemu.gameserver.services.bot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.commons.database.dao.DAOManager;
import com.aionemu.gameserver.configs.main.BotsConfig;
import com.aionemu.gameserver.dao.PlayerDAO;
import com.aionemu.gameserver.model.Gender;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.utils.ThreadPoolManager;
import com.aionemu.gameserver.utils.Util;
import com.aionemu.gameserver.world.World;
import com.aionemu.gameserver.configs.main.GeoDataConfig;
import com.aionemu.gameserver.world.geo.GeoService;
import com.aionemu.gameserver.world.knownlist.KnownList;

/**
 * Central manager / lifecycle for AI bot players.
 *
 * <ul>
 *   <li>Starts the configured number of bots on server startup (via a startup hook in GameServer).</li>
 *   <li>Supports runtime spawning / despawning through the {@code //bot} GM command.</li>
 *   <li>Schedules a periodic think tick per bot that drives {@link PlayerBotAI}.</li>
 * </ul>
 *
 * <p>
 * Bots are persisted: on startup a bot named "BotN" is loaded from the database if it already exists
 * (preserving level / progress), otherwise a fresh level-1 character is created. Their "home" position
 * is distributed across the starting location using the map's zone definitions, so they spread out
 * naturally and never clump on the spawn point.
 * </p>
 */
public class BotManager {

	private static final BotManager instance = new BotManager();

	public static BotManager getInstance() {
		return instance;
	}

	private final Map<Integer, PlayerBotAI> bots = new ConcurrentHashMap<>();
	private final Map<Integer, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();
	private final Logger log = LoggerFactory.getLogger(BotManager.class);
	private int nameCounter = 0;
	private int spawnIndex = 0;

	public void start() {
		if (!BotsConfig.BOTS_ENABLED) {
			log.info("Bot system is disabled.");
			return;
		}
		log.info("Starting bot system: spawning " + BotsConfig.BOTS_COUNT + " bot(s).");
		for (int i = 0; i < BotsConfig.BOTS_COUNT; i++) {
			spawnBot();
		}
	}

	public synchronized void spawnBot() {
		try {
			int myIndex = spawnIndex++;
			String name = nextName();
			int existingObjId = DAOManager.getDAO(PlayerDAO.class).getPlayerIdByName(Util.convertName(name));

			Player player;
			if (existingObjId > 0) {
				player = BotPlayerFactory.loadExistingBot(name);
				if (player == null) {
					// loading failed for some reason - fall back to a fresh character
					player = createFresh(name);
				} else {
					log.info("Loaded existing bot " + name + " (level " + player.getLevel() + ").");
				}
			} else {
				player = createFresh(name);
			}

			if (player == null)
				return;

			// distribute this bot's "home" across the starting location using an even spiral
			// anchored to the (correct-ground) spawn point, biased toward walkable zone polygons
			int mapId = player.getWorldId();
			// keep homes close to the (correct-ground) spawn point: the 4-arg getZ we use can only find
			// terrain at or below the spawn height, so large spreads that climb hills would sink bots
			// underground. A tight radius on the open starting pad keeps everyone grounded.
			float spread = Math.min(Math.max(BotsConfig.BOTS_SPREAD_RADIUS, 35), 50);
			float[] home = ZoneHomePicker.pickHome(mapId, player.getX(), player.getY(), player.getZ(), myIndex,
				spread);
			// snap the home height to the terrain. Use the 4-arg getZ with the (correct, grounded)
			// spawn Z as the reference: it casts a ray DOWN from there, so it finds the ground below
			// the bot rather than a tree canopy / roof (the 2-arg getZ returns the TOP surface and
			// would drop the bot in the air). This only works while the home stays near the spawn
			// height, so keep the spread radius small (see ZoneHomePicker call below).
			home[2] = GeoService.getInstance().getZ(mapId, home[0], home[1], player.getZ(), 0f,
				player.getInstanceId());
			if (!GeoDataConfig.GEO_ENABLE)
				home[2] = player.getZ();
			World.getInstance().setPosition(player, mapId, home[0], home[1], home[2], (byte) 0);

			player.setKnownlist(new KnownList(player));
			World.getInstance().storeObject(player);
			World.getInstance().spawn(player);
			PlayerBotAI ai = new PlayerBotAI(player);
			bots.put(player.getObjectId(), ai);
			ScheduledFuture<?> task = ThreadPoolManager.getInstance().scheduleAtFixedRate(ai::tick,
				BotsConfig.BOTS_THINK_INTERVAL, BotsConfig.BOTS_THINK_INTERVAL);
			tasks.put(player.getObjectId(), task);
			log.info("Spawned bot " + name + " (" + player.getRace() + " " + player.getPlayerClass() + ", level "
				+ player.getLevel() + ") at " + (int) home[0] + "," + (int) home[1] + "," + (int) home[2] + ".");
		} catch (Exception e) {
			log.error("Failed to spawn bot: " + e.getMessage(), e);
		}
	}

	/** Creates a brand-new level-1 bot with a random race / class / gender. */
	private Player createFresh(String name) {
		Race race = Math.random() < 0.5 ? Race.ELYOS : Race.ASMODIANS;
		PlayerClass clazz = randomStarterClass();
		Gender gender = Math.random() < 0.5 ? Gender.MALE : Gender.FEMALE;
		return BotPlayerFactory.createBot(name, race, clazz, gender, 1);
	}

	public void despawnAll() {
		for (ScheduledFuture<?> task : tasks.values()) {
			if (task != null)
				task.cancel(true);
		}
		tasks.clear();
		for (Integer objId : bots.keySet()) {
			Player p = World.getInstance().findPlayer(objId);
			if (p != null) {
				World.getInstance().despawn(p);
				World.getInstance().removeObject(p);
			}
		}
		bots.clear();
		log.info("All bots despawned.");
	}

	private String nextName() {
		return "Bot" + (++nameCounter);
	}

	private PlayerClass randomStarterClass() {
		PlayerClass[] starters = { PlayerClass.WARRIOR, PlayerClass.SCOUT, PlayerClass.MAGE, PlayerClass.PRIEST };
		return starters[(int) (Math.random() * starters.length)];
	}
}
