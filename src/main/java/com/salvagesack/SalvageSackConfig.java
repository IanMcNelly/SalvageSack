package com.salvagesack;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("salvagesack")
public interface SalvageSackConfig extends Config
{
	@ConfigItem(
		keyName = "sortOption",
		name = "Sort Items By",
		description = "Choose how to sort items within each shipwreck type",
		position = 1
	)
	default SortOption sortOption()
	{
		return SortOption.ALPHABETICAL;
	}

	@ConfigItem(
		keyName = "sortDescending",
		name = "Sort Descending",
		description = "Sort items in descending order (highest to lowest)",
		position = 2
	)
	default boolean sortDescending()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showUnobtainedDrops",
		name = "Show Unobtained Drops",
		description = "Show items you haven't received yet from the drop table (dimmed)",
		position = 3
	)
	default boolean showUnobtainedDrops()
	{
		return true;
	}

	@ConfigItem(
		keyName = "displayRateAs1inX",
		name = "Show Rate as '1 in X'",
		description = "Display drop rates as fractional odds (e.g. 1/750) instead of percentages",
		position = 4
	)
	default boolean displayRateAs1inX()
	{
		return true;
	}
}
