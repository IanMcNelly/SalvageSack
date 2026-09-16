package com.salvagesack;

import lombok.Data;

/**
 * Represents a salvage item drop with tracking information
 */
@Data
public class SalvageItem
{
	private int itemId;
	private final String itemName;
	private int dropCount;      // Number of times this item was dropped (for rate calculation)
	private int totalQuantity;  // Total quantity received (for display)
	private double expectedDropRate; // Expected rate from wiki (e.g., 0.1 for 10%)

	public SalvageItem(int itemId, String itemName, double expectedDropRate)
	{
		this.itemId = itemId;
		this.itemName = itemName;
		this.dropCount = 0;
		this.totalQuantity = 0;
		this.expectedDropRate = expectedDropRate;
	}


	/**
	 * Full constructor for deserialization with quantity
	 */
	public SalvageItem(int itemId, String itemName, int dropCount, int totalQuantity, double expectedDropRate)
	{
		this.itemId = itemId;
		this.itemName = itemName;
		this.dropCount = dropCount;
		this.totalQuantity = totalQuantity;
		this.expectedDropRate = expectedDropRate;
	}

	/**
	 * Increment the drop count for this item (single drop)
	 */
	public void incrementDropCount()
	{
		this.dropCount++;
		this.totalQuantity++;
	}

	/**
	 * Record a drop with a specific quantity
	 * Counts as 1 drop for rate calculation, but adds full quantity to total
	 * @param quantity The quantity received in this drop
	 */
	public void recordDrop(int quantity)
	{
		this.dropCount++;           // Only 1 drop for rate calculation
		this.totalQuantity += quantity;  // Full quantity for display
	}

	/**
	 * Add to the drop count for this item (legacy method)
	 * @param amount Amount to add
	 * @deprecated Use recordDrop(quantity) instead
	 */
	@Deprecated
	public void addDropCount(int amount)
	{
		this.dropCount += amount;
		this.totalQuantity += amount;
	}

	/**
	 * Calculate current drop rate based on total loots
	 * @param totalLoots Total number of loots from this shipwreck type
	 * @return Current drop rate as a decimal (e.g., 0.15 for 15%)
	 */
	public double getCurrentDropRate(int totalLoots)
	{
		if (totalLoots == 0)
		{
			return 0.0;
		}
		return (double) dropCount / totalLoots;
	}
}
