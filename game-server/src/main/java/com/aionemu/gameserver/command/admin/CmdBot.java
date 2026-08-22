package com.aionemu.gameserver.command.admin;

import com.aionemu.gameserver.command.BaseCommand;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.services.bot.BotManager;
import com.aionemu.gameserver.utils.PacketSendUtility;

/**
 * GM command to control AI bot players.
 *
 * Usage:
 *   //bot spawn [count]  - spawn N additional bots (default 1)
 *   //bot clear          - despawn all bots
 *   //bot list           - hint
 */
public class CmdBot extends BaseCommand {

	@Override
	public void execute(Player admin, String... params) {
		if (params.length == 0) {
			showHelp(admin);
			return;
		}
		String cmd = params[0];
		switch (cmd.toLowerCase()) {
			case "spawn": {
				int n = 1;
				if (params.length > 1) {
					try {
						n = Integer.parseInt(params[1]);
					} catch (NumberFormatException e) {
						n = 1;
					}
				}
				for (int i = 0; i < n; i++)
					BotManager.getInstance().spawnBot();
				PacketSendUtility.sendMessage(admin, "Spawned " + n + " bot(s).");
				break;
			}
			case "clear":
				BotManager.getInstance().despawnAll();
				PacketSendUtility.sendMessage(admin, "All bots despawned.");
				break;
			case "list":
				PacketSendUtility.sendMessage(admin,
					"Active bots are listed in the game-server log (search for 'Spawned bot').");
				break;
			default:
				showHelp(admin);
		}
	}

	public void onFail(Player admin, String message) {
		showHelp(admin);
	}
}
