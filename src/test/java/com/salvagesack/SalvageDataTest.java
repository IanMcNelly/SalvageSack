package com.salvagesack;

import org.junit.Test;
import static org.junit.Assert.*;

public class SalvageDataTest
{
	@Test
	public void testSalvageItemTracking()
	{
		SalvageItem item = new SalvageItem(1, "Test Item", 0.25);
		assertEquals("Test Item", item.getItemName());
		assertEquals(1, item.getItemId());
		assertEquals(0, item.getDropCount());
		assertEquals(0.25, item.getExpectedDropRate(), 0.001);

		item.incrementDropCount();
		assertEquals(1, item.getDropCount());

		// Current drop rate should be 50% after 2 loots with 1 drop
		assertEquals(0.5, item.getCurrentDropRate(2), 0.001);
	}

	@Test
	public void testSalvageDataRecording()
	{
		SalvageData data = new SalvageData(ShipwreckType.SMALL);
		assertEquals(ShipwreckType.SMALL, data.getShipwreckType());
		assertEquals(0, data.getTotalLoots());
		assertTrue(data.getItems().isEmpty());

		data.incrementTotalLoots();
		data.recordLoot(1, "Item 1", 0.5);
		
		assertEquals(1, data.getTotalLoots());
		assertEquals(1, data.getItems().size());
		assertEquals(1, data.getItems().get(1).getDropCount());

		data.incrementTotalLoots();
		data.recordLoot(1, "Item 1", 0.5);
		data.recordLoot(2, "Item 2", 0.25);

		assertEquals(2, data.getTotalLoots());
		assertEquals(2, data.getItems().size());
		assertEquals(2, data.getItems().get(1).getDropCount());
		assertEquals(1, data.getItems().get(2).getDropCount());
	}

	@Test
	public void testShipwreckTypeFromString()
	{
		assertEquals(ShipwreckType.SMALL, ShipwreckType.fromString("Small"));
		assertEquals(ShipwreckType.LARGE, ShipwreckType.fromString("Large"));
		assertEquals(ShipwreckType.PIRATE, ShipwreckType.fromString("Plundered"));
		assertEquals(ShipwreckType.MERCENARY, ShipwreckType.fromString("Martial"));
		assertEquals(ShipwreckType.UNKNOWN, ShipwreckType.fromString("Random Text"));
	}

	@Test
	public void testDropRateCalculation()
	{
		SalvageItem item = new SalvageItem(1, "Test", 0.2);
		
		// No loots yet
		assertEquals(0.0, item.getCurrentDropRate(0), 0.001);
		
		// 1 drop in 5 loots = 20%
		item.incrementDropCount();
		assertEquals(0.2, item.getCurrentDropRate(5), 0.001);
		
		// 3 drops in 10 loots = 30%
		item.incrementDropCount();
		item.incrementDropCount();
		assertEquals(0.3, item.getCurrentDropRate(10), 0.001);
	}

	@Test
	public void testRecordLootWithQuantity()
	{
		SalvageData data = new SalvageData(ShipwreckType.BARRACUDA);
		data.incrementTotalLoots();
		data.recordLoot(100, "Coins", 0.05, 50);

		assertEquals(1, data.getTotalLoots());
		SalvageItem item = data.getItems().get(100);
		assertNotNull(item);
		assertEquals(1, item.getDropCount()); // 1 drop event
		assertEquals(50, item.getTotalQuantity()); // 50 coins total
		assertEquals(1.0, item.getCurrentDropRate(1), 0.001); // 1 drop in 1 sort

		// Second sort drops 25 coins
		data.incrementTotalLoots();
		data.recordLoot(100, "Coins", 0.05, 25);
		assertEquals(2, data.getTotalLoots());
		assertEquals(2, item.getDropCount());
		assertEquals(75, item.getTotalQuantity());
		assertEquals(1.0, item.getCurrentDropRate(2), 0.001);
	}

	@Test
	public void testExpectedDropRateUpdate()
	{
		SalvageData data = new SalvageData(ShipwreckType.SMALL);
		data.incrementTotalLoots();
		data.recordLoot(31989, "Boat bottle (empty)", 0.00133, 1);
		SalvageItem item = data.getItems().get(31989);
		assertEquals(0.00133, item.getExpectedDropRate(), 0.00001);

		// Record again with updated rate (e.g. from updated drop_rates.json)
		data.recordLoot(31989, "Boat bottle (empty)", 0.00133333, 1);
		assertEquals(0.00133333, item.getExpectedDropRate(), 0.000001);
	}

	@Test
	public void testLastUpdatedTimestamp()
	{
		SalvageData data = new SalvageData(ShipwreckType.MERCHANT);
		assertEquals(0L, data.getLastUpdated());

		long before = System.currentTimeMillis();
		data.incrementTotalLoots();
		long after = System.currentTimeMillis();

		assertTrue(data.getLastUpdated() >= before);
		assertTrue(data.getLastUpdated() <= after);
	}

	@Test
	public void testRecordLootLegacyIdMigration()
	{
		SalvageData data = new SalvageData(ShipwreckType.SMALL);
		int legacyHashId = "Boat bottle (empty)".hashCode() & 0x7FFFFFFF;
		int canonicalId = 31989;

		// Existing drop recorded under legacy hash ID
		data.incrementTotalLoots();
		data.recordLoot(legacyHashId, "Boat bottle (empty)", 0.001, 2);
		assertEquals(1, data.getItems().size());
		assertTrue(data.getItems().containsKey(legacyHashId));

		// Next drop recorded under canonical ID
		data.incrementTotalLoots();
		data.recordLoot(canonicalId, "Boat bottle (empty)", 0.00133333, 1);

		// Must NOT create a duplicate split row
		assertEquals(1, data.getItems().size());
		assertFalse("Old hash key should be removed", data.getItems().containsKey(legacyHashId));
		assertTrue("Canonical key should be present", data.getItems().containsKey(canonicalId));

		SalvageItem migrated = data.getItems().get(canonicalId);
		assertNotNull(migrated);
		assertEquals(canonicalId, migrated.getItemId());
		assertEquals(2, migrated.getDropCount()); // 1 + 1 drops
		assertEquals(3, migrated.getTotalQuantity()); // 2 + 1 quantity
		assertEquals(0.00133333, migrated.getExpectedDropRate(), 0.000001);
	}

	@Test
	public void testNormalizeItemIds()
	{
		SalvageData data = new SalvageData(ShipwreckType.FISHERMANS);
		int legacyId1 = 999999;
		int canonicalId1 = 12345;

		// Put two entries directly into items map to simulate loaded legacy data with split entries
		data.getItems().put(legacyId1, new SalvageItem(legacyId1, "Sailors' amulet (inert)", 1, 1, 0.0005));
		data.getItems().put(canonicalId1, new SalvageItem(canonicalId1, "Sailors' amulet (inert)", 1, 2, 0.0005));

		assertEquals(2, data.getItems().size());

		boolean modified = data.normalizeItemIds(name -> {
			if ("Sailors' amulet (inert)".equalsIgnoreCase(name))
			{
				return canonicalId1;
			}
			return -1;
		});

		assertTrue(modified);
		assertEquals(1, data.getItems().size());
		SalvageItem merged = data.getItems().get(canonicalId1);
		assertNotNull(merged);
		assertEquals(canonicalId1, merged.getItemId());
		assertEquals(2, merged.getDropCount()); // 1 + 1 drops
		assertEquals(3, merged.getTotalQuantity()); // 1 + 2 quantity
	}

	@Test
	public void testNormalizeItemIdsDoesNotOverwriteValidIdWithHashFallbackOrNegative()
	{
		SalvageData data = new SalvageData(ShipwreckType.FISHERMANS);
		int validId = 12345;
		String itemName = "Boat bottle (empty)";
		data.getItems().put(validId, new SalvageItem(validId, itemName, 5, 5, 0.00133));

		// Simulate lookup returning a hash fallback (transient failure in lookupItemId)
		int hashFallback = itemName.hashCode() & 0x7FFFFFFF;
		boolean modifiedWithHash = data.normalizeItemIds(name -> hashFallback);
		assertFalse("Should not modify item ID when lookup returns hash fallback", modifiedWithHash);
		assertEquals("Item ID should remain valid ID", validId, data.getItems().get(validId).getItemId());

		// Simulate lookup returning -1 (unresolved in lookupCanonicalItemId)
		boolean modifiedWithNeg = data.normalizeItemIds(name -> -1);
		assertFalse("Should not modify item ID when lookup returns -1", modifiedWithNeg);
		assertEquals("Item ID should remain valid ID", validId, data.getItems().get(validId).getItemId());
	}
}
