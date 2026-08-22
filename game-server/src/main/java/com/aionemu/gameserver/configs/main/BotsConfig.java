package com.aionemu.gameserver.configs.main;

import com.aionemu.commons.configuration.Property;

/**
 * Configuration for the AI bot player system.
 *
 * Loaded from ./config/main/bots.properties by {@code Config.load()}.
 */
public class BotsConfig {

	@Property(key = "gameserver.bots.enable", defaultValue = "false")
	public static boolean BOTS_ENABLED;

	@Property(key = "gameserver.bots.count", defaultValue = "10")
	public static int BOTS_COUNT;

	@Property(key = "gameserver.bots.spawnmap", defaultValue = "110010000")
	public static int BOTS_SPAWN_MAP;

	@Property(key = "gameserver.bots.thinkinterval", defaultValue = "1000")
	public static int BOTS_THINK_INTERVAL;

	@Property(key = "gameserver.bots.maxlevel", defaultValue = "55")
	public static int BOTS_MAX_LEVEL;
	@Property(key = "gameserver.bots.spreadradius", defaultValue = "80")
	public static int BOTS_SPREAD_RADIUS;
	@Property(key = "gameserver.bots.wanderradius", defaultValue = "22")
	public static int BOTS_WANDER_RADIUS;

	/**
	 * Whether bots should actively use their learned skills in combat (so other
	 * players see casting animations via the normal SM_CASTSPELL packets).
	 */
	@Property(key = "gameserver.bots.useskills", defaultValue = "true")
	public static boolean BOTS_USE_SKILLS;

	/**
	 * Probability (0..1) that a bot casts a known skill instead of a basic attack
	 * when it is in range of its target.
	 */
	@Property(key = "gameserver.bots.skillchance", defaultValue = "0.5")
	public static float BOTS_SKILL_CHANCE;

	/**
	 * Base id used to generate synthetic login account ids for bots when no real
	 * login-server database is configured. Keep this well above normal account ids.
	 */
	@Property(key = "gameserver.bots.accountbase", defaultValue = "1000000000")
	public static long BOTS_ACCOUNT_BASE;

	/**
	 * Optional JDBC url of the login-server database. When set, a real login account
	 * row is created per bot (matching the user's "one account per bot" requirement).
	 * Leave empty to only persist the character in the game-server database.
	 */
	@Property(key = "gameserver.bots.logindb.url", defaultValue = "")
	public static String BOTS_LOGINDB_URL;

	@Property(key = "gameserver.bots.logindb.user", defaultValue = "root")
	public static String BOTS_LOGINDB_USER;

	@Property(key = "gameserver.bots.logindb.password", defaultValue = "")
	public static String BOTS_LOGINDB_PASSWORD;
}
