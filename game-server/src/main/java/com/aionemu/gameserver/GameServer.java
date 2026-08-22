package com.aionemu.gameserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.commons.database.DB;
import com.aionemu.commons.database.DatabaseFactory;
import com.aionemu.commons.database.dao.DAOManager;
import com.aionemu.commons.network.NioServer;
import com.aionemu.commons.network.ServerCfg;
import com.aionemu.commons.services.CronService;
import com.aionemu.commons.utils.ExitCode;
import com.aionemu.gameserver.ai2.AI2Engine;
import com.aionemu.gameserver.cache.HTMLCache;
import com.aionemu.gameserver.command.CmdTeleService;
import com.aionemu.gameserver.command.CommandService;
import com.aionemu.gameserver.configs.Config;
import com.aionemu.gameserver.configs.custom.RecursiveAddConf;
import com.aionemu.gameserver.configs.custom.WebShopConf;
import com.aionemu.gameserver.configs.main.AIConfig;
import com.aionemu.gameserver.configs.main.CustomConfig;
import com.aionemu.gameserver.configs.main.DredgionConfig;
import com.aionemu.gameserver.configs.main.EventsConfig;
import com.aionemu.gameserver.configs.main.GSConfig;
import com.aionemu.gameserver.configs.main.SiegeConfig;
import com.aionemu.gameserver.configs.main.ThreadConfig;
import com.aionemu.gameserver.configs.main.WeddingsConfig;
import com.aionemu.gameserver.configs.network.NetworkConfig;
import com.aionemu.gameserver.dao.PlayerDAO;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.eventengine.EventWebShop;
import com.aionemu.gameserver.eventengine.RecursiveAdd;
import com.aionemu.gameserver.instance.InstanceEngine;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.siege.Influence;
import com.aionemu.gameserver.network.BannedMacManager;
import com.aionemu.gameserver.network.aion.GameConnectionFactoryImpl;
import com.aionemu.gameserver.network.chatserver.ChatServer;
import com.aionemu.gameserver.network.loginserver.LoginServer;
import com.aionemu.gameserver.questEngine.QuestEngine;
import com.aionemu.gameserver.services.AdminService;
import com.aionemu.gameserver.services.AnnouncementService;
import com.aionemu.gameserver.services.BrokerService;
import com.aionemu.gameserver.services.DebugService;
import com.aionemu.gameserver.services.EventService;
import com.aionemu.gameserver.services.ExchangeService;
import com.aionemu.gameserver.services.FlyRingService;
import com.aionemu.gameserver.services.GameTimeService;
import com.aionemu.gameserver.services.LimitedItemTradeService;
import com.aionemu.gameserver.services.NpcShoutsService;
import com.aionemu.gameserver.services.PeriodicSaveService;
import com.aionemu.gameserver.services.PetitionService;
import com.aionemu.gameserver.services.RoadService;
import com.aionemu.gameserver.services.ShieldService;
import com.aionemu.gameserver.services.SiegeService;
import com.aionemu.gameserver.services.WeatherService;
import com.aionemu.gameserver.services.WeddingService;
import com.aionemu.gameserver.services.abyss.AbyssRankUpdateService;
import com.aionemu.gameserver.services.drop.DropRegistrationService;
import com.aionemu.gameserver.services.instance.DredgionService2;
import com.aionemu.gameserver.services.instance.InstanceService;
import com.aionemu.gameserver.services.player.PlayerEventService;
import com.aionemu.gameserver.services.player.PlayerLimitService;
import com.aionemu.gameserver.services.reward.RewardService;
import com.aionemu.gameserver.services.transfers.PlayerTransferService;
import com.aionemu.gameserver.services.tvt.TvtService;
import com.aionemu.gameserver.spawnengine.DayTimeSpawnEngine;
import com.aionemu.gameserver.spawnengine.InstanceRiftSpawnManager;
import com.aionemu.gameserver.spawnengine.RiftSpawnManager;
import com.aionemu.gameserver.spawnengine.SpawnEngine;
import com.aionemu.gameserver.taskmanager.TaskManagerFromDB;
import com.aionemu.gameserver.taskmanager.tasks.PacketBroadcaster;
import com.aionemu.gameserver.utils.AEVersions;
import com.aionemu.gameserver.utils.ThreadPoolManager;
import com.aionemu.gameserver.utils.ThreadUncaughtExceptionHandler;
import com.aionemu.gameserver.utils.Util;
import com.aionemu.gameserver.utils.cron.ThreadPoolManagerRunnableRunner;
import com.aionemu.gameserver.utils.gametime.DateTimeUtil;
import com.aionemu.gameserver.utils.gametime.GameTimeManager;
import com.aionemu.gameserver.utils.idfactory.IDFactory;
import com.aionemu.gameserver.utils.javaagent.JavaAgentUtils;
import com.aionemu.gameserver.world.World;
import com.aionemu.gameserver.world.geo.GeoService;
import com.aionemu.gameserver.world.zone.ZoneService;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
@SuppressWarnings("deprecation")
public final class GameServer {

	private static final Logger log = LoggerFactory.getLogger(GameServer.class);

	private static final Path LOG_DIR = Path.of("log");
	private static final Path LOG_BACKUP_DIR = LOG_DIR.resolve("backup");
	private static final Path LOGBACK_CONFIG = Path.of("config", "slf4j-logback.xml");

	private static final ReentrantLock lock = new ReentrantLock();

	private static int elyosCount;
	private static int asmosCount;
	private static double elyosRatio;
	private static double asmosRatio;

	private static Set<StartupHook> startupHooks = new HashSet<>();

	private GameServer() {
	}

	public static void main(String[] args) {
		long startedAt = System.currentTimeMillis();

		try {
			initializeLogger();
			printHeader();

			initUtilityServicesAndConfig();

			loadCoreWorld();
			loadGameplayServices();
			loadSystemServices();

			GameServer gameServer = new GameServer();

			setAllPlayersOffline();
			cleanupRuntimePlayerVariables();

			startCustomServices();

			log.info("============================================================");
			log.info("[✓] NEXUS CONNECT GAME SERVER IS ONLINE AND READY TO PLAY! ");
			log.info("[✓] Total Boot Time: {} ms", (System.currentTimeMillis() - startedAt));
			log.info("=======================================================================");
			log.info("  _   _                           ____                            _    ");
			log.info(" | \\ | | _____  ___   _ ___      / ___|___  _ __  _ __   ___  ___| |_  ");
			log.info(" |  \\| |/ _ \\ \\/ / | | / __|    | |   / _ \\| '_ \\| '_ \\ / _ \\/ __| __| ");
			log.info(" | |\\  |  __/>  <| |_| \\__ \\    | |__| (_) | | | | | | |  __/ (__| |_  ");
			log.info(" |_| \\_|\\___/_/\\_\\\\__,_|___/     \\____\\___/|_| |_|_| |_|\\___|\\___|\\__| ");
			log.info("                                                                       ");
			log.info("                 Aion 2.7 - Java 25 Edition                            ");
			log.info("           [ Modernized and Modified by Nexus Connect  ]                   ");
			log.info("                    [ weplaynexus.com  ]                                ");
			log.info("=======================================================================");
			gameServer.startServers();

			Runtime.getRuntime().addShutdownHook(ShutdownHook.getInstance());

			initializeFactionRatioHook();
			initializeBotHook();
			onStartup();

		} catch (Throwable e) {
			log.error("Failed to start Nexus Connect Game Server.", e);
			System.exit(ExitCode.CODE_ERROR);
		}
	}

	private static void initializeLogger() {
		backupOldLogs();
		configureLogback();
	}

	private static void backupOldLogs() {
		try {
			Files.createDirectories(LOG_BACKUP_DIR);

			try (DirectoryStream<Path> logs = Files.newDirectoryStream(LOG_DIR, "*.log")) {
				Path backupFile = LOG_BACKUP_DIR
						.resolve(new SimpleDateFormat("yyyy-MM-dd-HHmmss").format(new Date()) + ".zip");

				boolean hasLogs = false;

				try (OutputStream fileOut = Files.newOutputStream(backupFile);
						ZipOutputStream zipOut = new ZipOutputStream(fileOut)) {

					zipOut.setMethod(ZipOutputStream.DEFLATED);
					zipOut.setLevel(Deflater.BEST_COMPRESSION);

					byte[] buffer = new byte[8192];

					for (Path logFile : logs) {
						if (!Files.isRegularFile(logFile)) {
							continue;
						}

						hasLogs = true;
						zipOut.putNextEntry(new ZipEntry(logFile.getFileName().toString()));

						try (InputStream input = Files.newInputStream(logFile)) {
							int length;
							while ((length = input.read(buffer)) > 0) {
								zipOut.write(buffer, 0, length);
							}
						}

						zipOut.closeEntry();
					}
				}

				if (hasLogs) {
					try (DirectoryStream<Path> oldLogs = Files.newDirectoryStream(LOG_DIR, "*.log")) {
						for (Path oldLog : oldLogs) {
							Files.deleteIfExists(oldLog);
						}
					}
				} else {
					Files.deleteIfExists(backupFile);
				}
			}
		} catch (IOException e) {
			System.err.println("Failed to backup old log files: " + e.getMessage());
		}
	}

	private static void configureLogback() {
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

		try {
			JoranConfigurator configurator = new JoranConfigurator();
			configurator.setContext(context);

			context.reset();
			configurator.doConfigure(LOGBACK_CONFIG.toString());
		} catch (JoranException e) {
			throw new IllegalStateException("Failed to configure loggers.", e);
		}
	}

private static void printHeader() {
		log.info("=======================================================================");
		log.info("  _   _                           ____                            _    ");
		log.info(" | \\ | | _____  ___   _ ___      / ___|___  _ __  _ __   ___  ___| |_  ");
		log.info(" |  \\| |/ _ \\ \\/ / | | / __|    | |   / _ \\| '_ \\| '_ \\ / _ \\/ __| __| ");
		log.info(" | |\\  |  __/>  <| |_| \\__ \\    | |__| (_) | | | | | | |  __/ (__| |_  ");
		log.info(" |_| \\_|\\___/_/\\_\\\\__,_|___/     \\____\\___/|_| |_|_| |_|\\___|\\___|\\__| ");
		log.info("                                                                       ");
		log.info("                 Aion 2.7 - Java 25 Edition                            ");
		log.info("           [ Modernized and Modified by Nexus Connect  ]                   ");
		log.info("                    [ weplaynexus.com  ]                                ");
		log.info("=======================================================================");
	}

	private static void initUtilityServicesAndConfig() {
		Thread.setDefaultUncaughtExceptionHandler(new ThreadUncaughtExceptionHandler());

		if (JavaAgentUtils.isConfigured()) {
			log.info("JavaAgent callback support is configured.");
		}

		CronService.initSingleton(ThreadPoolManagerRunnableRunner.class);

		log.info("Loading configuration...");
		Config.load();

		DateTimeUtil.init();

		Util.printSection("Database");
		DatabaseFactory.init();

		Util.printSection("DAO");
		DAOManager.init();

		Util.printSection("Threads");
		ThreadConfig.load();
		ThreadPoolManager.getInstance();
	}

	private static void loadCoreWorld() {
		DataManager.getInstance();

		Util.printSection("IDFactory");
		IDFactory.getInstance();

		Util.printSection("Zone");
		ZoneService.getInstance().load();

		Util.printSection("World");
		World.getInstance();

		Util.printSection("Drops");
		DropRegistrationService.getInstance();

		setAllPlayersOffline();

		BannedMacManager.getInstance();
		GameTimeManager.startClock();

		Util.printSection("Geodata");
		GeoService.getInstance().initializeGeo();

		Util.printSection("Quests");
		QuestEngine.getInstance().load();

		Util.printSection("Instances");
		InstanceEngine.getInstance().load();

		Util.printSection("AI2");
		AI2Engine.getInstance().load();

		Util.printSection("Siege Location Data");
		SiegeService.getInstance().initSiegeLocations();

		Util.printSection("Limits");
		LimitedItemTradeService.getInstance().start();

		if (CustomConfig.LIMITS_ENABLED) {
			PlayerLimitService.getInstance().scheduleUpdate();
		}

		Util.printSection("Spawns");
		SpawnEngine.spawnAll();
		RiftSpawnManager.spawnAll();
		InstanceRiftSpawnManager.spawnAll();
		DayTimeSpawnEngine.spawnAll();

		Util.printSection("Siege Schedule Initialization");
		SiegeService.getInstance().initSieges();
	}

	private static void loadGameplayServices() {
		Util.printSection("Task Managers");
		PacketBroadcaster.getInstance();

		GameTimeService.getInstance();
		AnnouncementService.getInstance();
		DebugService.getInstance();
		WeatherService.getInstance();
		BrokerService.getInstance();
		Influence.getInstance();
		ExchangeService.getInstance();
		PeriodicSaveService.getInstance();
		PetitionService.getInstance();

		if (AIConfig.SHOUTS_ENABLE) {
			NpcShoutsService.getInstance();
		}

		InstanceService.load();
		FlyRingService.getInstance();
		RoadService.getInstance();
		HTMLCache.getInstance();
		AbyssRankUpdateService.getInstance().scheduleUpdate();
		TaskManagerFromDB.getInstance();

		if (SiegeConfig.SIEGE_SHIELD_ENABLED) {
			ShieldService.getInstance();
		}

		if (DredgionConfig.DREDGION2_ENABLE) {
			Util.printSection("Dredgion");
			DredgionService2.getInstance().start();
		}

		if (CustomConfig.ENABLE_REWARD_SERVICE) {
			RewardService.getInstance();
		}

		if (EventsConfig.EVENT_ENABLED) {
			PlayerEventService.getInstance();
		}

		if (EventsConfig.ENABLE_EVENT_SERVICE) {
			EventService.getInstance().start();
		}

		if (WeddingsConfig.WEDDINGS_ENABLE) {
			WeddingService.getInstance();
		}

		Util.printSection("Nexus Events");
		TvtService.getInstance().initTvt();

		AdminService.getInstance();
		PlayerTransferService.getInstance();
	}

	private static void loadSystemServices() {
		Util.printSection("System");
		AEVersions.printFullVersionInfo();

		printSystemInfo();

		CommandService.getInstance();
		CmdTeleService.getInstance();
	}

	private static void setAllPlayersOffline() {
		DAOManager.getDAO(PlayerDAO.class).setPlayersOffline(false);
	}

	private static void cleanupRuntimePlayerVariables() {
		try {
			DB.prepareStatement("DELETE FROM player_vars WHERE param=\"groupCancelCounter\"").execute();
			DB.prepareStatement("DELETE FROM player_vars WHERE param=\"dp\"").execute();
		} catch (SQLException e) {
			log.error("Failed to cleanup runtime player variables.", e);
		}
	}

	private static void startCustomServices() {
		if (RecursiveAddConf.enabled) {
			RecursiveAdd.startRecursiveAddTask();
			log.info("RecursiveAdd service started.");
		} else {
			log.info("RecursiveAdd service disabled.");
		}

		if (WebShopConf.WEBSHOP_ENABLED) {
			EventWebShop.startEventWebShopTask();
			log.info("WebShop service started.");
		} else {
			log.info("WebShop service disabled.");
		}
	}

	private void startServers() {
		Util.printSection("Starting Network");

		NioServer nioServer = new NioServer(NetworkConfig.NIO_READ_WRITE_THREADS,
				new ServerCfg(NetworkConfig.GAME_BIND_ADDRESS, NetworkConfig.GAME_PORT, "Game Connections",
						new GameConnectionFactoryImpl()));

		LoginServer loginServer = LoginServer.getInstance();
		ChatServer chatServer = ChatServer.getInstance();

		loginServer.setNioServer(nioServer);
		chatServer.setNioServer(nioServer);

		nioServer.connect();
		loginServer.connect();

		if (GSConfig.ENABLE_CHAT_SERVER) {
			chatServer.connect();
		}
	}

	private static void initializeFactionRatioHook() {
		if (!GSConfig.FACTIONS_RATIO_LIMITED) {
			return;
		}

		addStartupHook(() -> {
			lock.lock();

			try {
				asmosCount = DAOManager.getDAO(PlayerDAO.class).getCharacterCountForRace(Race.ASMODIANS);
				elyosCount = DAOManager.getDAO(PlayerDAO.class).getCharacterCountForRace(Race.ELYOS);
				computeRatios();
			} catch (Exception e) {
				log.error("Failed to initialize faction ratios.", e);
			} finally {
				lock.unlock();
			}

			displayRatios(false);
		});
	}

	private static void initializeBotHook() {
		addStartupHook(() -> {
			try {
				com.aionemu.gameserver.services.bot.BotManager.getInstance().start();
			} catch (Exception e) {
				log.error("Failed to start bot system.", e);
			}
		});
	}

	public static synchronized void addStartupHook(StartupHook hook) {
		if (startupHooks != null) {
			startupHooks.add(hook);
		} else {
			hook.onStartup();
		}
	}

	private static synchronized void onStartup() {
		Set<StartupHook> hooks = startupHooks;
		startupHooks = null;

		for (StartupHook hook : hooks) {
			hook.onStartup();
		}
	}

	public static void updateRatio(Race race, int value) {
		lock.lock();

		try {
			switch (race) {
			case ASMODIANS -> asmosCount += value;
			case ELYOS -> elyosCount += value;
			default -> {
			}
			}

			computeRatios();
		} catch (Exception e) {
			log.error("Failed to update faction ratio.", e);
		} finally {
			lock.unlock();
		}

		displayRatios(true);
	}

	private static void computeRatios() {
		if (asmosCount <= GSConfig.FACTIONS_RATIO_MINIMUM && elyosCount <= GSConfig.FACTIONS_RATIO_MINIMUM) {
			asmosRatio = 50.0;
			elyosRatio = 50.0;
			return;
		}

		int total = asmosCount + elyosCount;

		if (total <= 0) {
			asmosRatio = 50.0;
			elyosRatio = 50.0;
			return;
		}

		asmosRatio = asmosCount * 100.0 / total;
		elyosRatio = elyosCount * 100.0 / total;
	}

	private static void displayRatios(boolean updated) {
		log.info("FACTIONS RATIO {}: E {}% / A {}%", updated ? "UPDATED" : "", String.format("%.1f", elyosRatio),
				String.format("%.1f", asmosRatio));
	}

	public static double getRatiosFor(Race race) {
		return switch (race) {
		case ASMODIANS -> asmosRatio;
		case ELYOS -> elyosRatio;
		default -> 0.0;
		};
	}

	public static int getCountFor(Race race) {
		return switch (race) {
		case ASMODIANS -> asmosCount;
		case ELYOS -> elyosCount;
		default -> 0;
		};
	}

	private static void printSystemInfo() {
		log.info("System Information");
		log.info("------------------------------------------------------------");
		log.info("Java Version : {}", System.getProperty("java.version"));
		log.info("Java Vendor  : {}", System.getProperty("java.vendor"));
		log.info("OS Name      : {}", System.getProperty("os.name"));
		log.info("OS Version   : {}", System.getProperty("os.version"));
		log.info("OS Arch      : {}", System.getProperty("os.arch"));
		log.info("Processors   : {}", Runtime.getRuntime().availableProcessors());
		log.info("Max Memory   : {} MB", Runtime.getRuntime().maxMemory() / 1024 / 1024);
		log.info("Total Memory : {} MB", Runtime.getRuntime().totalMemory() / 1024 / 1024);
		log.info("Free Memory  : {} MB", Runtime.getRuntime().freeMemory() / 1024 / 1024);
		log.info("------------------------------------------------------------");
	}

	public interface StartupHook {
		void onStartup();
	}
}