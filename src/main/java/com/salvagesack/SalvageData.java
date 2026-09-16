package com.salvagesack;

import lombok.Data;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Stores all salvage tracking data for a specific shipwreck type.
 * <p>
 * Each shipwreck type maintains its own loot statistics including total sorts,
 * individual item drop counts, and a timestamp for UI ordering.
 * </p>
 * <p>
 * The panel displays shipwreck sections sorted by {@link #lastUpdated} so that
 * the most recently active shipwreck always appears at the top, regardless of
 * whether it is expanded or collapsed.
 * </p>
 */
@Data
public class SalvageData
{
	private final ShipwreckType shipwreckType;
	private int totalLoots;
	private final Map<Integer, SalvageItem> items; // itemId -> SalvageItem

	/**
	 * Timestamp (milliseconds since epoch) of the last loot recorded for this shipwreck.
	 * Used to sort shipwreck panels in the UI with most recently updated at the top.
	 */
	private long lastUpdated;

	public SalvageData(ShipwreckType shipwreckType)
	{
		this.shipwreckType = shipwreckType;
		this.totalLoots = 0;
		this.items = new ConcurrentHashMap<>();
		this.lastUpdated = 0;
	}

	/**
	 * Constructor for deserialization
	 */
	public SalvageData(ShipwreckType shipwreckType, int totalLoots, Map<Integer, SalvageItem> items)
	{
		this.shipwreckType = shipwreckType;
		this.totalLoots = totalLoots;
		this.items = items;
		this.lastUpdated = 0;
	}

	/**
	 * Record a loot drop
	 * @param itemId The item ID that was looted
	 * @param itemName The item name
	 * @param expectedDropRate Expected drop rate for this item
	 */
	public void recordLoot(int itemId, String itemName, double expectedDropRate)
	{
		recordLoot(itemId, itemName, expectedDropRate, 1);
	}

	/**
	 * Record a loot drop with quantity
	 * Counts as 1 drop for rate calculation, but tracks full quantity
	 * @param itemId The item ID that was looted
	 * @param itemName The item name
	 * @param expectedDropRate Expected drop rate for this item
	 * @param quantity Number of items looted (for display, not rate calc)
	 */
	public synchronized void recordLoot(int itemId, String itemName, double expectedDropRate, int quantity)
	{
		SalvageItem existingSameName = null;
		for (Map.Entry<Integer, SalvageItem> entry : items.entrySet())
		{
			if (entry.getKey() != itemId && isSameItemName(entry.getValue().getItemName(), itemName))
			{
				existingSameName = entry.getValue();
				items.remove(entry.getKey());
				break;
			}
		}

		SalvageItem item = items.get(itemId);
		if (item != null && existingSameName != null)
		{
			item.setDropCount(item.getDropCount() + existingSameName.getDropCount());
			item.setTotalQuantity(item.getTotalQuantity() + existingSameName.getTotalQuantity());
		}
		else if (item == null && existingSameName != null)
		{
			int fallbackHash = itemName.hashCode() & 0x7FFFFFFF;
			if (itemId == fallbackHash)
			{
				item = existingSameName;
				items.put(item.getItemId(), item);
			}
			else
			{
				item = existingSameName;
				item.setItemId(itemId);
				items.put(itemId, item);
			}
		}
		else if (item == null)
		{
			item = new SalvageItem(itemId, itemName, expectedDropRate);
			items.put(itemId, item);
		}

		if (expectedDropRate > 0)
		{
			item.setExpectedDropRate(expectedDropRate);
		}
		item.recordDrop(quantity);  // 1 drop for rate, full quantity for display
	}

	/**
	 * Normalizes item IDs using the provided lookup function to ensure legacy items
	 * (e.g. keyed by hash-fallback) are re-keyed to their canonical item IDs.
	 * Merges drop counts and quantities if multiple entries map to the same ID.
	 *
	 * @param idLookup Lookup function mapping item name to canonical item ID
	 * @return true if any item was re-keyed or merged, false otherwise
	 */
	public synchronized boolean normalizeItemIds(Function<String, Integer> idLookup)
	{
		if (idLookup == null || items.isEmpty())
		{
			return false;
		}

		boolean modified = false;
		Map<Integer, SalvageItem> normalized = new ConcurrentHashMap<>();

		for (SalvageItem item : items.values())
		{
			int lookupId = idLookup.apply(item.getItemName());
			int fallbackHash = item.getItemName().hashCode() & 0x7FFFFFFF;

			int canonicalId;
			if (lookupId <= 0 || lookupId == fallbackHash)
			{
				canonicalId = item.getItemId();
			}
			else
			{
				canonicalId = lookupId;
			}

			if (canonicalId != item.getItemId())
			{
				modified = true;
				item.setItemId(canonicalId);
			}

			SalvageItem existing = normalized.get(canonicalId);
			if (existing != null)
			{
				existing.setDropCount(existing.getDropCount() + item.getDropCount());
				existing.setTotalQuantity(existing.getTotalQuantity() + item.getTotalQuantity());
				if (item.getExpectedDropRate() > 0 && existing.getExpectedDropRate() <= 0)
				{
					existing.setExpectedDropRate(item.getExpectedDropRate());
				}
				modified = true;
			}
			else
			{
				normalized.put(canonicalId, item);
			}
		}

		if (modified)
		{
			items.clear();
			items.putAll(normalized);
		}

		return modified;
	}

	/**
	 * Increment total loot count and update the last modified timestamp.
	 * <p>
	 * This method should be called once per salvage sort action. The timestamp
	 * update ensures this shipwreck type will be displayed at the top of the
	 * panel, as shipwrecks are sorted by most recently updated.
	 * </p>
	 */
	public void incrementTotalLoots()
	{
		this.totalLoots++;
		this.lastUpdated = System.currentTimeMillis();
	}

	private static boolean isSameItemName(String name1, String name2)
	{
		if (name1 == null || name2 == null)
		{
			return false;
		}
		if (name1.equalsIgnoreCase(name2))
		{
			return true;
		}
		String n1 = name1.toLowerCase().replace(" (unf)", "(unf)").trim();
		String n2 = name2.toLowerCase().replace(" (unf)", "(unf)").trim();
		return n1.equalsIgnoreCase(n2);
	}
}
