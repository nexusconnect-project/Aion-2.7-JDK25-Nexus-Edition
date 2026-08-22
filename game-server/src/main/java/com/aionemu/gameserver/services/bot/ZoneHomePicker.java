package com.aionemu.gameserver.services.bot;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.aionemu.gameserver.world.MapRegion;
import com.aionemu.gameserver.world.World;
import com.aionemu.gameserver.world.WorldMapInstance;

/**
 * Distributes bot "home" positions around the race's starting spawn point.
 *
 * <p>
 * Bots must stay where the ground height ({@code z}) is correct and where mobs actually are, otherwise
 * attacks fail the server's {@code GeoService.canSee} line-of-sight check and the bot just stands next
 * to a mob doing nothing. We therefore anchor homes to the spawn point (correct {@code z}) and spread
 * them evenly using a golden-angle spiral so they don't clump. The map's zone polygons
 * ({@code data/static_data/zones/zones_<mapId>.xml}) are used as a soft preference: points are nudged
 * into walkable zones when possible, but a validated region is what ultimately matters.
 * </p>
 */
public class ZoneHomePicker {

	private static final Set<String> SKIP_ZONE_TYPES = new HashSet<>(Arrays.asList(
		"SUB", "WATER", "FLY", "DEAD", "BUILDING", "PVP", "ARTIFACT", "FLAG", "BOSS"));

	private static final Map<Integer, List<Poly>> cache = new HashMap<>();

	private static final class Poly {
		final float[] pts;

		Poly(float[] pts) {
			this.pts = pts;
		}
	}

	private static List<Poly> load(int mapId) {
		List<Poly> polys = cache.get(mapId);
		if (polys != null)
			return polys;
		polys = new ArrayList<>();
		File f = new File("data/static_data/zones/zones_" + mapId + ".xml");
		if (f.exists()) {
			try {
				Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f);
				NodeList zones = doc.getElementsByTagName("zone");
				for (int i = 0; i < zones.getLength(); i++) {
					Element z = (Element) zones.item(i);
					if (!"POLYGON".equalsIgnoreCase(z.getAttribute("area_type")))
						continue;
					String zt = z.getAttribute("zone_type").toUpperCase();
					if (SKIP_ZONE_TYPES.contains(zt))
						continue;
					NodeList pts = z.getElementsByTagName("point");
					if (pts.getLength() < 3)
						continue;
					float[] poly = new float[pts.getLength() * 2];
					for (int j = 0; j < pts.getLength(); j++) {
						Element p = (Element) pts.item(j);
						poly[j * 2] = Float.parseFloat(p.getAttribute("x"));
						poly[j * 2 + 1] = Float.parseFloat(p.getAttribute("y"));
					}
					polys.add(new Poly(poly));
				}
				if (polys.isEmpty()) {
					for (int i = 0; i < zones.getLength(); i++) {
						Element z = (Element) zones.item(i);
						if (!"POLYGON".equalsIgnoreCase(z.getAttribute("area_type")))
							continue;
						NodeList pts = z.getElementsByTagName("point");
						if (pts.getLength() < 3)
							continue;
						float[] poly = new float[pts.getLength() * 2];
						for (int j = 0; j < pts.getLength(); j++) {
							Element p = (Element) pts.item(j);
							poly[j * 2] = Float.parseFloat(p.getAttribute("x"));
							poly[j * 2 + 1] = Float.parseFloat(p.getAttribute("y"));
						}
						polys.add(new Poly(poly));
					}
				}
			} catch (Exception e) {
				// parse failure -> fall back to spawn point
			}
		}
		cache.put(mapId, polys);
		return polys;
	}

	private static boolean pointInPoly(float x, float y, float[] poly) {
		int n = poly.length / 2;
		boolean inside = false;
		for (int i = 0, j = n - 1; i < n; j = i++) {
			float xi = poly[i * 2], yi = poly[i * 2 + 1];
			float xj = poly[j * 2], yj = poly[j * 2 + 1];
			if (((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi))
				inside = !inside;
		}
		return inside;
	}

	private static boolean insideAnyPoly(List<Poly> polys, float x, float y) {
		for (Poly poly : polys) {
			if (pointInPoly(x, y, poly.pts))
				return true;
		}
		return false;
	}

	/**
	 * Picks a home position for bot {@code index}. Bots are spread on an even golden-angle spiral around
	 * the (correct-ground) spawn point and kept inside a loaded map region. Falls back to the spawn point.
	 */
	public static float[] pickHome(int mapId, float spawnX, float spawnY, float spawnZ, int index, float radius) {
		List<Poly> polys = load(mapId);
		Random rnd = new Random(index * 2654435761L + 1);
		float angle = index * 2.399963f; // golden angle -> no clumping
		float rad = (float) Math.sqrt(index + 1) * (radius / 9f);
		if (rad > radius)
			rad = radius;

		for (int attempt = 0; attempt < 14; attempt++) {
			float x = spawnX + (float) Math.cos(angle) * rad + (rnd.nextFloat() - 0.5f) * 14f;
			float y = spawnY + (float) Math.sin(angle) * rad + (rnd.nextFloat() - 0.5f) * 14f;
			if (!isRegionValid(mapId, x, y, spawnZ))
				continue;
			// prefer walkable zone polygons (uses zones XML), but don't fail if none match
			if (!polys.isEmpty() && !insideAnyPoly(polys, x, y) && attempt < 10)
				continue;
			return new float[] { x, y, spawnZ };
		}
		return new float[] { spawnX, spawnY, spawnZ };
	}

	private static boolean isRegionValid(int mapId, float x, float y, float z) {
		try {
			WorldMapInstance wmi = World.getInstance().getWorldMap(mapId).getWorldMapInstance();
			MapRegion r = wmi.getRegion(x, y, z);
			return r != null;
		} catch (Exception e) {
			return false;
		}
	}
}
