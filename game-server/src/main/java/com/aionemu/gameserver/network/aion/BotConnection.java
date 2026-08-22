package com.aionemu.gameserver.network.aion;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import org.slf4j.LoggerFactory;

import com.aionemu.commons.network.AConnection;

/**
 * Headless, write-disabled connection that also acts as a <em>virtual client</em>: bot AI code
 * dispatches real {@link AionClientPacket}s (CM_MOVE / CM_ATTACK / CM_CASTSPELL / ...) through
 * {@link #dispatch(AionClientPacket)}, which runs them through the exact same handler pipeline a
 * real human client would trigger. This makes the server treat the bot 100% like a player — no
 * fragile direct calls into {@code PlayerController}/{@code MoveController}.
 */
public class BotConnection extends AionConnection {

	public BotConnection() throws IOException {
		super(createLoopbackChannel(), null);
		setWriteDisabled();
		// The bot is "in game" so client-packet state validation (CM_ATTACK/CM_MOVE require IN_GAME) passes.
		setState(State.IN_GAME);
	}

	/**
	 * Runs a client packet exactly as the network reader would: binds it to this connection and
	 * executes {@code run()} (state validation + {@code runImpl()}). The packet's fields must already
	 * be populated (via {@link #setPacketField(AionClientPacket, String, Object)} or a read buffer)
	 * because we skip the wire decoding — the bot produces the same logical data a real client would.
	 */
	public void dispatch(AionClientPacket packet) {
		packet.setConnection(this);
		packet.run();
	}

	/** Sets a (possibly private) field on a client packet — used to populate packet data without wire encoding. */
	public static void setPacketField(AionClientPacket packet, String name, Object value) {
		try {
			Field f = packet.getClass().getDeclaredField(name);
			f.setAccessible(true);
			f.set(packet, value);
		} catch (Exception e) {
			LoggerFactory.getLogger(BotConnection.class)
				.warn("BotConnection.setPacketField failed for " + name + ": " + e.getMessage());
		}
	}

	private static SocketChannel createLoopbackChannel() throws IOException {
		ServerSocketChannel ssc = ServerSocketChannel.open();
		ssc.socket().bind(new InetSocketAddress("127.0.0.1", 0));
		SocketChannel client = SocketChannel.open();
		client.connect(ssc.getLocalAddress());
		ssc.close();
		return client;
	}

	private void setWriteDisabled() {
		try {
			Field closed = AConnection.class.getDeclaredField("closed");
			closed.setAccessible(true);
			closed.set(this, true);
		} catch (Exception e) {
			LoggerFactory.getLogger(BotConnection.class)
				.warn("BotConnection: could not mark write-disabled, packets may error: " + e.getMessage());
		}
	}

	/** Bots are never closed through the network; ignore close requests. */
	@Override
	public void closeNow() {
		// no-op
	}
}
