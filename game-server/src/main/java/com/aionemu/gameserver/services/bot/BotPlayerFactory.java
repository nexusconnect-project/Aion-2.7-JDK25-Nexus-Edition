package com.aionemu.gameserver.services.bot;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.commons.database.dao.DAOManager;
import com.aionemu.gameserver.configs.main.BotsConfig;
import com.aionemu.gameserver.dao.PlayerAppearanceDAO;
import com.aionemu.gameserver.dao.PlayerDAO;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.Gender;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.account.Account;
import com.aionemu.gameserver.model.account.PlayerAccountData;
import com.aionemu.gameserver.model.gameobjects.player.FriendList.Status;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.player.PlayerAppearance;
import com.aionemu.gameserver.model.gameobjects.player.PlayerCommonData;
import com.aionemu.gameserver.model.gameobjects.player.emotion.EmotionList;
import com.aionemu.gameserver.model.gameobjects.player.motion.MotionList;
import com.aionemu.gameserver.model.items.storage.PlayerStorage;
import com.aionemu.gameserver.model.items.storage.StorageType;
import com.aionemu.gameserver.utils.idfactory.IDFactory;
import com.aionemu.gameserver.network.aion.BotConnection;
import com.aionemu.gameserver.services.AccountService;
import com.aionemu.gameserver.services.SkillLearnService;
import com.aionemu.gameserver.services.player.PlayerService;
import com.aionemu.gameserver.utils.Util;

/**
 * Creates a fully persisted bot character (account + player) in the databases and
 * attaches a headless {@link BotConnection} so the player behaves like a real, online player
 * without any socket backing it.
 */
public class BotPlayerFactory {

	private static final Logger log = LoggerFactory.getLogger(BotPlayerFactory.class);
	private static final AtomicLong accountCounter = new AtomicLong(BotsConfig.BOTS_ACCOUNT_BASE);
	private static final java.util.Random RND = new java.util.Random();

	private static int rnd(int min, int max) {
		return min + RND.nextInt(max - min + 1);
	}

	/**
	 * Builds a fully populated {@link PlayerAppearance} for a bot. A real character gets all these
	 * values from the client during creation; a bot created with a default (all-zero) appearance has
	 * height == 0.0f, which makes the client render the model at zero scale (invisible). We mirror the
	 * client creation flow here and assign valid, varied values.
	 */
	private static PlayerAppearance createBotAppearance() {
		PlayerAppearance a = new PlayerAppearance();
		a.setVoice(rnd(0, 3));
		a.setSkinRGB(0xFFE0BD);
		a.setHairRGB(0x3B2A1A);
		a.setEyeRGB(0x4B3A2A);
		a.setLipRGB(0xC07A6A);
		a.setFace(rnd(1, 10));
		a.setHair(rnd(1, 24));
		a.setDeco(rnd(0, 3));
		a.setTattoo(rnd(0, 3));
		a.setFaceContour(rnd(1, 9));
		a.setExpression(rnd(1, 9));
		a.setJawLine(rnd(1, 9));
		a.setForehead(rnd(1, 9));
		a.setEyeHeight(rnd(1, 9));
		a.setEyeSpace(rnd(1, 9));
		a.setEyeWidth(rnd(1, 9));
		a.setEyeSize(rnd(1, 9));
		a.setEyeShape(rnd(1, 9));
		a.setEyeAngle(rnd(1, 9));
		a.setBrowHeight(rnd(1, 9));
		a.setBrowAngle(rnd(1, 9));
		a.setBrowShape(rnd(1, 9));
		a.setNose(rnd(1, 9));
		a.setNoseBridge(rnd(1, 9));
		a.setNoseWidth(rnd(1, 9));
		a.setNoseTip(rnd(1, 9));
		a.setCheek(rnd(1, 9));
		a.setLipHeight(rnd(1, 9));
		a.setMouthSize(rnd(1, 9));
		a.setLipSize(rnd(1, 9));
		a.setSmile(rnd(1, 9));
		a.setLipShape(rnd(1, 9));
		a.setJawHeigh(rnd(1, 9));
		a.setChinJut(rnd(1, 9));
		a.setEarShape(rnd(1, 9));
		a.setHeadSize(rnd(1, 9));
		a.setNeck(rnd(1, 9));
		a.setNeckLength(rnd(1, 9));
		a.setShoulderSize(rnd(1, 9));
		a.setTorso(rnd(1, 9));
		a.setChest(rnd(1, 9));
		a.setWaist(rnd(1, 9));
		a.setHips(rnd(1, 9));
		a.setArmThickness(rnd(1, 9));
		a.setHandSize(rnd(1, 9));
		a.setLegThicnkess(rnd(1, 9));
		a.setFootSize(rnd(1, 9));
		a.setFacialRate(rnd(1, 9));
		a.setArmLength(rnd(1, 9));
		a.setLegLength(rnd(1, 9));
		a.setShoulders(rnd(1, 9));
		a.setFaceShape(rnd(1, 9));
		a.setHeight(1.0f);
		return a;
	}


	/**
	 * Loads an already-persisted bot from the database (preserving its level and progress) and brings
	 * it online with a headless {@link BotConnection}. Returns null if the bot cannot be loaded.
	 */
	public static Player loadExistingBot(String name) {
		try {
			String convName = Util.convertName(name);
			int objId = DAOManager.getDAO(PlayerDAO.class).getPlayerIdByName(convName);
			if (objId <= 0)
				return null;
			int accountId = DAOManager.getDAO(PlayerDAO.class).getAccountIdByName(convName);
			if (accountId <= 0)
				return null;

			Account account = AccountService.getAccount(accountId, name, null, (byte) 0, (byte) 0, 0);
			if (account == null || account.isEmpty())
				return null;

			Player player = PlayerService.getPlayer(objId, account);
			if (player == null)
				return null;

			if (player.getMotions() == null)
				player.setMotions(new MotionList(player));
			if (player.getEmotions() == null)
				player.setEmotions(new EmotionList(player));

			// mark the bot as online in the friend list so it shows up in the player search (/who)
			try {
				player.getFriendList().setStatus(Status.ONLINE);
			} catch (Exception e) {
				// friend list not loaded yet - ignore
			}

			// grant every skill the bot's saved level is eligible for (saved characters only
			// keep the skills they had; addMissingSkills backfills any missing ones)
			try {
				SkillLearnService.addMissingSkills(player);
			} catch (Exception e) {
				// best-effort
			}

			BotConnection conn = new BotConnection();
			conn.setAccount(account);
			conn.setActivePlayer(player);
			player.setClientConnection(conn);
			return player;
		} catch (Exception e) {
			log.error("Failed to load existing bot " + name + ": " + e.getMessage(), e);
			return null;
		}
	}

	public static Player createBot(String name, Race race, PlayerClass playerClass, Gender gender, int level) {
		String convName = Util.convertName(name);

		long accountId = ensureLoginAccount(name);

		PlayerCommonData pcd = new PlayerCommonData(IDFactory.getInstance().nextId());
		pcd.setName(convName);
		pcd.setLevel(level);
		pcd.setGender(gender);
		pcd.setRace(race);
		pcd.setPlayerClass(playerClass);

		PlayerAppearance appearance = createBotAppearance();

		Account account = new Account((int) accountId);
		account.setName(name);
		account.setAccountWarehouse(new PlayerStorage(StorageType.ACCOUNT_WAREHOUSE));

		// builds the in-memory player (spawn position, starting skills + items) and persists it
		Player newPlayer = PlayerService.newPlayer(pcd, appearance, account);
		boolean saved = PlayerService.storeNewPlayer(newPlayer, name, (int) accountId);
		if (!saved) {
			log.warn("Bot " + name + " could not be persisted; it will not survive a restart.");
		}

		// bring the character fully online: this is the normal player-login initialization
		// (effect controller, abyss rank, npc factions, motion/emotion lists, storages, etc.)
		account.addPlayerAccountData(new PlayerAccountData(pcd, null, appearance, null, null));
		Player player = PlayerService.getPlayer(pcd.getPlayerObjId(), account);

		// safety net: guarantee motion/emotion lists are never null for the spawn 'see' path
		if (player.getMotions() == null) {
			player.setMotions(new MotionList(player));
		}
		if (player.getEmotions() == null) {
			player.setEmotions(new EmotionList(player));
		}

		// mark the bot as online in the friend list so it shows up in the player search (/who)
		try {
			player.getFriendList().setStatus(Status.ONLINE);
		} catch (Exception e) {
			// friend list not loaded yet - ignore
		}

		try {
			BotConnection conn = new BotConnection();
			conn.setAccount(account);
			conn.setActivePlayer(player);
			player.setClientConnection(conn);
		} catch (Exception e) {
			log.error("Failed to attach bot connection for " + name + ": " + e.getMessage(), e);
		}

		// backfill any class/level skills the starter kit may have missed
		try {
			SkillLearnService.addMissingSkills(player);
		} catch (Exception e) {
			// best-effort
		}

		return player;
	}

	/**
	 * Ensures a login-server account exists for the bot. Best-effort: when no login DB is
	 * configured it returns a synthetic id. Failures are logged and fall back to the synthetic id.
	 */
	private static long ensureLoginAccount(String name) {
		long synthetic = accountCounter.getAndIncrement();
		String url = BotsConfig.BOTS_LOGINDB_URL;
		if (url == null || url.trim().isEmpty()) {
			return synthetic;
		}
		try (Connection con = DriverManager.getConnection(url, BotsConfig.BOTS_LOGINDB_USER,
			BotsConfig.BOTS_LOGINDB_PASSWORD)) {
			try (PreparedStatement ps = con.prepareStatement("SELECT id FROM account_data WHERE name = ?")) {
				ps.setString(1, name);
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.next()) {
						return rs.getLong("id");
					}
				}
			}
			try (PreparedStatement ps = con.prepareStatement(
				"INSERT INTO account_data(`name`,`password`,access_level,membership,activated,last_server,last_ip,last_mac,ip_force,toll,age_limit) "
					+ "VALUES (?,?,0,0,1,1,'127.0.0.1','00:00:00:00:00:00',0,0,0)",
				Statement.RETURN_GENERATED_KEYS)) {
				ps.setString(1, name);
				ps.setString(2, "bot");
				ps.executeUpdate();
				try (ResultSet keys = ps.getGeneratedKeys()) {
					if (keys.next()) {
						return keys.getLong(1);
					}
				}
			}
		} catch (Exception e) {
			log.warn("Bot login account creation failed for " + name + ", using synthetic id: " + e.getMessage());
		}
		return synthetic;
	}
}
