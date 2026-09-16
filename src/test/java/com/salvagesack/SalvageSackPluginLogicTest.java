package com.salvagesack;

import net.runelite.api.ItemID;
import org.junit.Test;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

public class SalvageSackPluginLogicTest
{
	private static final Pattern SALVAGE_PATTERN = Pattern.compile(
		"You sort through the (.+?) salvage and find: (\\d+) x (.+?)\\.",
		Pattern.CASE_INSENSITIVE
	);

	@Test
	public void testSalvageChatMessagePattern()
	{
		String msg = "You sort through the Martial salvage and find: 1 x Adamant 2h sword.";
		Matcher matcher = SALVAGE_PATTERN.matcher(msg);
		assertTrue(matcher.find());
		assertEquals("Martial", matcher.group(1));
		assertEquals("1", matcher.group(2));
		assertEquals("Adamant 2h sword", matcher.group(3));

		// Test Small salvage with multiple quantity
		String msg2 = "You sort through the Small salvage and find: 50 x Coins.";
		Matcher matcher2 = SALVAGE_PATTERN.matcher(msg2);
		assertTrue(matcher2.find());
		assertEquals("Small", matcher2.group(1));
		assertEquals("50", matcher2.group(2));
		assertEquals("Coins", matcher2.group(3));

		// Test Fisherman's shipwreck (Fishy salvage)
		String msg3 = "You sort through the Fishy salvage and find: 1 x Boat bottle (empty).";
		Matcher matcher3 = SALVAGE_PATTERN.matcher(msg3);
		assertTrue(matcher3.find());
		assertEquals("Fishy", matcher3.group(1));
		assertEquals("1", matcher3.group(2));
		assertEquals("Boat bottle (empty)", matcher3.group(3));
		assertEquals(ShipwreckType.FISHERMANS, ShipwreckType.fromString(matcher3.group(1)));
	}

	@Test
	public void testShipwreckTypeMappingForAllSalvageTypes()
	{
		assertEquals(ShipwreckType.SMALL, ShipwreckType.fromString("Small"));
		assertEquals(ShipwreckType.FISHERMANS, ShipwreckType.fromString("Fishy"));
		assertEquals(ShipwreckType.BARRACUDA, ShipwreckType.fromString("Barracuda"));
		assertEquals(ShipwreckType.LARGE, ShipwreckType.fromString("Large"));
		assertEquals(ShipwreckType.PIRATE, ShipwreckType.fromString("Plundered"));
		assertEquals(ShipwreckType.MERCENARY, ShipwreckType.fromString("Martial"));
		assertEquals(ShipwreckType.FREMENNIK, ShipwreckType.fromString("Fremennik"));
		assertEquals(ShipwreckType.MERCHANT, ShipwreckType.fromString("Opulent"));
	}

	@Test
	public void testKnownItemIdsMapping()
	{
		// Test that known untradeable items have correct canonical item IDs
		SalvageSackPlugin plugin = new SalvageSackPlugin();
		assertEquals(ItemID.BOAT_BOTTLE_EMPTY, plugin.lookupItemId("Boat bottle (empty)"));
		assertEquals(ItemID.BOAT_BOTTLE_EMPTY, plugin.lookupItemId("boat bottle (empty)"));
		assertEquals(ItemID.SAILORS_AMULET_INERT, plugin.lookupItemId("Sailors' amulet (inert)"));
		assertEquals(ItemID.SAILORS_AMULET, plugin.lookupItemId("Sailors' amulet"));
		assertEquals(ItemID.SOUP, plugin.lookupItemId("Soup"));
		assertEquals(ItemID.FACILITY_BOTTLE_EMPTY, plugin.lookupItemId("facility bottle (empty)"));
		assertEquals(ItemID.ADAMANT_BOLTSUNF, plugin.lookupItemId("Adamant bolts(unf)"));
		assertEquals(ItemID.ADAMANT_BOLTSUNF, plugin.lookupItemId("Adamant bolts (unf)"));
		assertEquals(ItemID.CASKET, plugin.lookupItemId("casket"));

		// Unknown item falls back to hash code
		String unknown = "Totally Random Nonexistent Item 123";
		int expectedHash = unknown.hashCode() & 0x7FFFFFFF;
		assertEquals(expectedHash, plugin.lookupItemId(unknown));

		// Second lookup returns same fallback value
		assertEquals(expectedHash, plugin.lookupItemId(unknown));

		// Fallback IDs must NOT be cached in itemIdCache
		assertFalse(plugin.getItemIdCache().containsKey(unknown.toLowerCase().trim()));
	}

	@Test
	public void testExpandedStateInitializationLogicWithRealPanel()
	{
		SalvageSackConfig config = new SalvageSackConfig() {};
		ItemIconManager iconManager = new ItemIconManager();
		SalvageSackPanel panel = new SalvageSackPanel(iconManager, config);

		Map<ShipwreckType, SalvageData> dataMap = new HashMap<>();
		panel.updateData(dataMap);

		// Initially with no data, inactive shipwreck types should be collapsed
		assertFalse(panel.isExpanded(ShipwreckType.SMALL));
		assertFalse(panel.isExpanded(ShipwreckType.MERCHANT));

		// Now user records their first loot drop for Small salvage
		SalvageData smallData = new SalvageData(ShipwreckType.SMALL);
		smallData.incrementTotalLoots();
		smallData.recordLoot(ItemID.BOAT_BOTTLE_EMPTY, "Boat bottle (empty)", 0.00133333, 1);
		dataMap.put(ShipwreckType.SMALL, smallData);

		// Invoke actual panel updateData
		panel.updateData(dataMap);

		// The newly active section must now be expanded!
		assertTrue(panel.isExpanded(ShipwreckType.SMALL));
		// Inactive section remains collapsed
		assertFalse(panel.isExpanded(ShipwreckType.MERCHANT));

		// User manually collapses the section
		panel.setExpanded(ShipwreckType.SMALL, false);
		assertFalse(panel.isExpanded(ShipwreckType.SMALL));

		// User manually expands the inactive section
		panel.setExpanded(ShipwreckType.MERCHANT, true);
		assertTrue(panel.isExpanded(ShipwreckType.MERCHANT));
	}

	@Test
	public void testUnobtainedItemDeduplicationWithUnfVariantsAndIds()
	{
		SalvageSackConfig config = new SalvageSackConfig() {};
		ItemIconManager iconManager = new ItemIconManager();
		SalvageSackPanel panel = new SalvageSackPanel(iconManager, config);

		File tempDir = new File(System.getProperty("java.io.tmpdir"), "salvagesack_dedup_" + System.currentTimeMillis());
		DropRateManager dropRateManager = new DropRateManager(tempDir, new com.google.gson.Gson());
		panel.setDropRateManager(dropRateManager);
		SalvageSackPlugin plugin = new SalvageSackPlugin();
		panel.setItemIdLookup(plugin::lookupItemId);

		// Record an obtained item with "Adamant bolts (unf)" in Mercenary
		SalvageData mercenaryData = new SalvageData(ShipwreckType.MERCENARY);
		mercenaryData.incrementTotalLoots();
		mercenaryData.recordLoot(ItemID.ADAMANT_BOLTSUNF, "Adamant bolts (unf)", 0.04098361, 50);

		// Call getSortedItems
		java.util.List<SalvageItem> items = panel.getSortedItems(ShipwreckType.MERCENARY, mercenaryData);

		// Count occurrences of Adamant bolts unf in the resulting list
		long boltRows = items.stream()
			.filter(i -> i.getItemName().toLowerCase().contains("adamant bolts"))
			.count();

		// Must be exactly 1 row (the obtained one), NOT duplicated with an unobtained x0 row!
		assertEquals("Should not have duplicate rows for Adamant bolts (unf)", 1, boltRows);
		SalvageItem boltItem = items.stream()
			.filter(i -> i.getItemName().toLowerCase().contains("adamant bolts"))
			.findFirst()
			.orElse(null);
		assertNotNull(boltItem);
		assertEquals(50, boltItem.getTotalQuantity());
		assertEquals(1, boltItem.getDropCount());

		// Test the reverse: user chat recorded legacy name "Adamant bolts(unf)" without space
		SalvageData mercenaryDataLegacy = new SalvageData(ShipwreckType.MERCENARY);
		mercenaryDataLegacy.incrementTotalLoots();
		mercenaryDataLegacy.recordLoot(ItemID.ADAMANT_BOLTSUNF, "Adamant bolts(unf)", 0.04098361, 25);

		java.util.List<SalvageItem> legacyItems = panel.getSortedItems(ShipwreckType.MERCENARY, mercenaryDataLegacy);
		long legacyBoltRows = legacyItems.stream()
			.filter(i -> i.getItemName().toLowerCase().contains("adamant bolts"))
			.count();
		assertEquals("Legacy spelling should also deduplicate against drop table", 1, legacyBoltRows);
	}

	@Test
	public void testUnobtainedItemDeduplicationWhenDropTableContainsBothAliases() throws Exception
	{
		SalvageSackConfig config = new SalvageSackConfig() {};
		ItemIconManager iconManager = new ItemIconManager();
		SalvageSackPanel panel = new SalvageSackPanel(iconManager, config);

		File tempDir = new File(System.getProperty("java.io.tmpdir"), "salvagesack_unobtained_" + System.currentTimeMillis());
		tempDir.mkdirs();
		tempDir.deleteOnExit();

		// Create a drop_rates.json where MERCENARY explicitly has both bolt aliases
		com.google.gson.Gson gson = new com.google.gson.Gson();
		com.google.gson.JsonObject root;
		try (java.io.InputStream is = getClass().getResourceAsStream("/drop_rates.json");
		     java.io.InputStreamReader reader = new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))
		{
			root = gson.fromJson(reader, com.google.gson.JsonObject.class);
		}
		// Add legacy alias key alongside canonical key in MERCENARY items
		root.getAsJsonObject("shipwrecks")
			.getAsJsonObject("MERCENARY")
			.getAsJsonObject("items")
			.addProperty("Adamant bolts(unf)", 0.0412);

		File userFile = new File(tempDir, "drop_rates.json");
		try (java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(new java.io.FileOutputStream(userFile), java.nio.charset.StandardCharsets.UTF_8))
		{
			gson.toJson(root, writer);
		}

		DropRateManager dropRateManager = new DropRateManager(tempDir, gson);
		panel.setDropRateManager(dropRateManager);
		SalvageSackPlugin plugin = new SalvageSackPlugin();
		panel.setItemIdLookup(plugin::lookupItemId);

		// In browsing mode (no drops recorded / data is null)
		java.util.List<SalvageItem> items = panel.getSortedItems(ShipwreckType.MERCENARY, null);

		long boltRows = items.stream()
			.filter(i -> i.getItemName().toLowerCase().contains("adamant bolts"))
			.count();

		assertEquals("Should deduplicate unobtained rows even if drop table contains both aliases", 1, boltRows);
	}

	@Test
	public void testLookupCanonicalItemIdVsLookupItemId()
	{
		SalvageSackPlugin plugin = new SalvageSackPlugin();

		// Known items return canonical ID for both methods
		assertEquals(ItemID.ADAMANT_BOLTSUNF, plugin.lookupItemId("Adamant bolts (unf)"));
		assertEquals(ItemID.ADAMANT_BOLTSUNF, plugin.lookupCanonicalItemId("Adamant bolts (unf)"));

		// Unknown items: lookupItemId returns hash fallback > 0
		String unknown = "Totally Random Nonexistent Item 123";
		int expectedHash = unknown.hashCode() & 0x7FFFFFFF;
		assertEquals(expectedHash, plugin.lookupItemId(unknown));

		// But lookupCanonicalItemId returns -1 (unresolved)
		assertEquals(-1, plugin.lookupCanonicalItemId(unknown));
	}
}
