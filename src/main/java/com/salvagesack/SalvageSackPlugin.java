package com.salvagesack;

import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.ItemID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.RuneLite;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import net.runelite.http.api.item.ItemPrice;

import javax.inject.Inject;
import javax.swing.SwingUtilities;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@PluginDescriptor(
	name = "Salvage Sack",
	description = "Tracks salvage loot from the Sailing skill by shipwreck type",
	tags = {"sailing", "salvage", "tracking", "loot"}
)
@SuppressWarnings("unused") // Fields and methods are used by RuneLite's dependency injection and event system
public class SalvageSackPlugin extends Plugin
{
	private static final Pattern SALVAGE_PATTERN = Pattern.compile(
		"You sort through the (.+?) salvage and find: (\\d+) x (.+?)\\.",
		Pattern.CASE_INSENSITIVE
	);

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ItemManager itemManager;

	@Inject
	private Gson gson;

	@Inject
	private net.runelite.api.Client client;

	@Inject
	private SalvageSackConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ScheduledExecutorService executor;

	private static final Map<String, Integer> KNOWN_ITEM_IDS = new HashMap<>();
	static
	{
		KNOWN_ITEM_IDS.put("boat bottle (empty)", ItemID.BOAT_BOTTLE_EMPTY);
		KNOWN_ITEM_IDS.put("sailors' amulet (inert)", ItemID.SAILORS_AMULET_INERT);
		KNOWN_ITEM_IDS.put("sailors' amulet", ItemID.SAILORS_AMULET);
		KNOWN_ITEM_IDS.put("rusty locket", ItemID.RUSTY_LOCKET);
		KNOWN_ITEM_IDS.put("mouldy block", ItemID.MOULDY_BLOCK);
		KNOWN_ITEM_IDS.put("dull knife", ItemID.DULL_KNIFE);
		KNOWN_ITEM_IDS.put("broken compass", ItemID.BROKEN_COMPASS);
		KNOWN_ITEM_IDS.put("rusty coin", ItemID.RUSTY_COIN);
		KNOWN_ITEM_IDS.put("broken sextant", ItemID.BROKEN_SEXTANT);
		KNOWN_ITEM_IDS.put("smashed mirror", ItemID.SMASHED_MIRROR);
		KNOWN_ITEM_IDS.put("mouldy doll", ItemID.MOULDY_DOLL);
		KNOWN_ITEM_IDS.put("soup", ItemID.SOUP);
		KNOWN_ITEM_IDS.put("casket", ItemID.CASKET);
		KNOWN_ITEM_IDS.put("clue scroll (beginner)", ItemID.CLUE_SCROLL_BEGINNER);
		KNOWN_ITEM_IDS.put("clue scroll (easy)", ItemID.CLUE_SCROLL_EASY);
		KNOWN_ITEM_IDS.put("clue scroll (medium)", ItemID.CLUE_SCROLL_MEDIUM);
		KNOWN_ITEM_IDS.put("clue scroll (hard)", ItemID.CLUE_SCROLL_HARD);
		KNOWN_ITEM_IDS.put("facility bottle (empty)", ItemID.FACILITY_BOTTLE_EMPTY);
		KNOWN_ITEM_IDS.put("salvor's paint", ItemID.SALVORS_PAINT);
		KNOWN_ITEM_IDS.put("sawmill coupon (wood plank)", ItemID.SAWMILL_COUPON_WOOD_PLANK);
		KNOWN_ITEM_IDS.put("sawmill coupon (oak plank)", ItemID.SAWMILL_COUPON_OAK_PLANK);
		KNOWN_ITEM_IDS.put("ensouled troll head", ItemID.ENSOULED_TROLL_HEAD);
		KNOWN_ITEM_IDS.put("elkhorn frag", ItemID.ELKHORN_FRAG);
		KNOWN_ITEM_IDS.put("pillar frag", ItemID.PILLAR_FRAG);
		KNOWN_ITEM_IDS.put("umbral frag", ItemID.UMBRAL_FRAG);
		KNOWN_ITEM_IDS.put("adamant bolts(unf)", ItemID.ADAMANT_BOLTSUNF);
		KNOWN_ITEM_IDS.put("adamant bolts (unf)", ItemID.ADAMANT_BOLTSUNF);
	}

	private static final long SAVE_DEBOUNCE_INTERVAL_MS = 4000;

	private final Map<String, Integer> itemIdCache = new ConcurrentHashMap<>();
	private final Set<String> pendingLookups = ConcurrentHashMap.newKeySet();
	private final AtomicBoolean rebuildScheduled = new AtomicBoolean(false);
	private SalvageSackPanel panel;
	private NavigationButton navButton;
	private SalvageDataManager dataManager;
	private DropRateManager dropRateManager;
	private Map<ShipwreckType, SalvageData> salvageDataMap;
	private volatile boolean hasUnsavedChanges = false;
	private long lastSaveTime = 0;
	private boolean needsReload = false;
	private final java.util.concurrent.atomic.AtomicInteger profileGenerationId = new java.util.concurrent.atomic.AtomicInteger(0);

	private synchronized void saveSalvageData()
	{
		hasUnsavedChanges = false;
		if (dataManager != null && salvageDataMap != null)
		{
			dataManager.saveData(salvageDataMap);
			lastSaveTime = System.currentTimeMillis();
		}
	}

	private void loadProfileData()
	{
		if (dataManager == null || salvageDataMap == null)
		{
			return;
		}
		
		int currentGenId = profileGenerationId.incrementAndGet();
		Map<ShipwreckType, SalvageData> loadedData = dataManager.loadData();
		
		if (loadedData == null)
		{
			log.warn("Failed to load profile data, preserving existing session state");
			return;
		}
		
		salvageDataMap.clear();
		if (!loadedData.isEmpty())
		{
			salvageDataMap.putAll(loadedData);
			log.info("Loaded {} shipwreck types from saved data", loadedData.size());
		}
		
		if (panel != null)
		{
			panel.updateData(salvageDataMap);
			requestPanelRebuild();
		}

		if (executor != null)
		{
			executor.execute(() -> {
				if (profileGenerationId.get() != currentGenId)
				{
					return;
				}

				boolean dataMigrated = false;
				for (SalvageData data : salvageDataMap.values())
				{
					if (profileGenerationId.get() != currentGenId)
					{
						return;
					}
					if (data != null && data.normalizeItemIds(this::lookupCanonicalItemId))
					{
						dataMigrated = true;
					}
				}
				if (dataMigrated && profileGenerationId.get() == currentGenId)
				{
					log.info("Migrated legacy item IDs in saved salvage data to canonical IDs");
					saveSalvageData();
					requestPanelRebuild();
				}
			});
		}
	}

	@Override
	protected void startUp()
	{
		log.info("Salvage Sack started!");

		salvageDataMap = new ConcurrentHashMap<>();
		
		ItemIconManager iconManager = new ItemIconManager();
		iconManager.setItemManager(itemManager);

		File runeliteDir = RuneLite.RUNELITE_DIR;
		if (runeliteDir == null)
		{
			runeliteDir = new File(System.getProperty("user.home"), ".runelite");
		}
		File legacyDataDirectory = new File(runeliteDir, "salvagesack");

		dataManager = new SalvageDataManager(configManager, legacyDataDirectory, gson);

		dropRateManager = new DropRateManager(legacyDataDirectory, gson);

		if (client != null && client.getGameState() == net.runelite.api.GameState.LOGGED_IN)
		{
			loadProfileData();
		}
		else
		{
			needsReload = true;
		}

		if (executor != null && dropRateManager != null)
		{
			executor.execute(() -> {
				for (ShipwreckType type : ShipwreckType.values())
				{
					if (type != ShipwreckType.UNKNOWN)
					{
						for (String name : dropRateManager.getAllItemRates(type).keySet())
						{
							lookupItemId(name);
						}
					}
				}
				log.debug("Pre-warmed item ID cache with {} entries", itemIdCache.size());
				
				boolean dataMigrated = false;
				for (SalvageData data : salvageDataMap.values())
				{
					if (data != null && data.normalizeItemIds(this::lookupCanonicalItemId))
					{
						dataMigrated = true;
					}
				}
				if (dataMigrated)
				{
					log.info("Migrated legacy item IDs in saved salvage data to canonical IDs after pre-warm");
					saveSalvageData();
				}

				SwingUtilities.invokeLater(() -> {
					if (panel != null)
					{
						panel.rebuild();
					}
				});
			});
		}

		panel = new SalvageSackPanel(iconManager, config);
		panel.setDropRateManager(dropRateManager);
		panel.setConfigManager(configManager);
		panel.setItemIdLookup(this::lookupItemId);
		panel.setOnResetShipwreck(this::resetShipwreckData);
		panel.setOnResetAll(this::resetAllData);
		panel.updateData(salvageDataMap);
		panel.rebuild();

		iconManager.setOnIconLoaded(() -> {
			if (panel != null)
			{
				panel.repaint();
			}
		});

		BufferedImage icon;
		try
		{
			icon = ImageUtil.loadImageResource(getClass(), "/icon.png");
		}
		catch (Exception e)
		{
			log.warn("Failed to load icon, using fallback", e);
			icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = icon.createGraphics();
			g.setColor(new Color(66, 134, 244)); // Blue color
			g.fillRect(0, 0, 16, 16);
			g.setColor(Color.WHITE);
			g.setFont(new Font("Arial", Font.BOLD, 12));
			g.drawString("S", 4, 13);
			g.dispose();
		}

		navButton = NavigationButton.builder()
			.tooltip("Salvage Sack")
			.icon(icon)
			.priority(5)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);
		log.info("Navigation button added to toolbar");
	}

	@Override
	protected void shutDown()
	{
		log.info("Salvage Sack stopped!");

		if (hasUnsavedChanges)
		{
			saveSalvageData();
			log.info("Saved salvage data on shutdown");
		}

		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
		}

		itemIdCache.clear();
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (hasUnsavedChanges && System.currentTimeMillis() - lastSaveTime >= SAVE_DEBOUNCE_INTERVAL_MS)
		{
			saveSalvageData();
			log.debug("Debounced salvage data save completed");
		}
	}

	@Subscribe
	public void onGameStateChanged(net.runelite.api.events.GameStateChanged event)
	{
		if (event.getGameState() == net.runelite.api.GameState.LOGIN_SCREEN)
		{
			if (hasUnsavedChanges)
			{
				saveSalvageData();
				log.debug("Saved salvage data on logout");
			}
			needsReload = true;
			profileGenerationId.incrementAndGet();
			salvageDataMap.clear();
			if (panel != null)
			{
				panel.updateData(salvageDataMap);
				requestPanelRebuild();
			}
		}
		else if (event.getGameState() == net.runelite.api.GameState.HOPPING)
		{
			if (hasUnsavedChanges)
			{
				saveSalvageData();
				log.debug("Saved salvage data on hop");
			}
			needsReload = true;
		}
		else if (event.getGameState() == net.runelite.api.GameState.LOGGED_IN)
		{
			if (needsReload)
			{
				loadProfileData();
				needsReload = false;
				log.debug("Reloaded salvage data for new profile");
			}
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if ("salvagesack".equals(event.getGroup()) && panel != null)
		{
			if (event.getKey() != null && event.getKey().startsWith("expanded_"))
			{
				return;
			}
			panel.onConfigChanged();
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && 
		    event.getType() != ChatMessageType.SPAM)
		{
			return;
		}

		String message = Text.removeTags(event.getMessage());

		Matcher matcher = SALVAGE_PATTERN.matcher(message);

		if (matcher.find())
		{
			String salvageType = matcher.group(1).trim();  // e.g., "martial"
			int quantity = Integer.parseInt(matcher.group(2).trim());  // e.g., 1
			String itemName = matcher.group(3).trim();  // e.g., "Adamant 2h sword"

			log.info("Parsed salvage: type='{}' quantity={} item='{}'", salvageType, quantity, itemName);

			ShipwreckType shipwreckType = ShipwreckType.fromString(salvageType);
			if (shipwreckType == ShipwreckType.UNKNOWN)
			{
				return;
			}

			SalvageData data = salvageDataMap.computeIfAbsent(
				shipwreckType, 
				SalvageData::new
			);

			data.incrementTotalLoots();

			int itemId = lookupItemId(itemName);

			double expectedRate = getExpectedDropRate(shipwreckType, itemName);

			data.recordLoot(itemId, itemName, expectedRate, quantity);

			panel.updateData(salvageDataMap);
			requestPanelRebuild();

			hasUnsavedChanges = true;
			if (System.currentTimeMillis() - lastSaveTime >= SAVE_DEBOUNCE_INTERVAL_MS)
			{
				saveSalvageData();
			}

			log.debug("Recorded salvage: {}x {} (ID: {}) from {}", quantity, itemName, itemId, shipwreckType);
		}
	}

	/**
	 * Look up an item ID by name using cache, known IDs, or the ItemManager.
	 */
	public int lookupItemId(String itemName)
	{
		if (itemName == null)
		{
			return -1;
		}

		String normalized = itemName.toLowerCase().trim();
		Integer cachedId = itemIdCache.get(normalized);
		if (cachedId != null)
		{
			return cachedId;
		}

		Integer knownId = KNOWN_ITEM_IDS.get(normalized);
		if (knownId != null)
		{
			itemIdCache.put(normalized, knownId);
			return knownId;
		}

		if (SwingUtilities.isEventDispatchThread())
		{
			if (executor != null && itemManager != null && pendingLookups.add(normalized))
			{
				executor.execute(() -> {
					try
					{
						int id = resolveItemIdFromManager(itemName, normalized);
						if (id > 0)
						{
							requestPanelRebuild();
						}
					}
					finally
					{
						pendingLookups.remove(normalized);
					}
				});
			}
			return itemName.hashCode() & 0x7FFFFFFF;
		}

		int resolvedId = resolveItemIdFromManager(itemName, normalized);
		if (resolvedId > 0)
		{
			return resolvedId;
		}

		return itemName.hashCode() & 0x7FFFFFFF;
	}

	private void requestPanelRebuild()
	{
		if (panel == null)
		{
			return;
		}

		if (rebuildScheduled.compareAndSet(false, true))
		{
			if (executor != null)
			{
				executor.schedule(() -> {
					rebuildScheduled.set(false);
					boolean needsSave = false;
					if (salvageDataMap != null)
					{
						for (SalvageData data : salvageDataMap.values())
						{
							if (data != null && data.normalizeItemIds(this::lookupCanonicalItemId))
							{
								needsSave = true;
							}
						}
					}
					
					final boolean finalNeedsSave = needsSave;
					SwingUtilities.invokeLater(() -> {
						if (finalNeedsSave)
						{
							hasUnsavedChanges = true;
						}
						
						if (panel != null)
						{
							panel.rebuild();
						}
					});
				}, 50, TimeUnit.MILLISECONDS);
			}
			else
			{
				rebuildScheduled.set(false);
				SwingUtilities.invokeLater(() -> {
					if (panel != null)
					{
						panel.rebuild();
					}
				});
			}
		}
	}

	/**
	 * Look up a canonical item ID by name without returning a positive hash fallback.
	 * Returns -1 if the ID could not be definitively resolved.
	 */
	public int lookupCanonicalItemId(String itemName)
	{
		if (itemName == null)
		{
			return -1;
		}

		String normalized = itemName.toLowerCase().trim();
		Integer cachedId = itemIdCache.get(normalized);
		if (cachedId != null)
		{
			return cachedId;
		}

		Integer knownId = KNOWN_ITEM_IDS.get(normalized);
		if (knownId != null)
		{
			itemIdCache.put(normalized, knownId);
			return knownId;
		}

		return resolveItemIdFromManager(itemName, normalized);
	}

	private int resolveItemIdFromManager(String itemName, String normalized)
	{
		Integer cached = itemIdCache.get(normalized);
		if (cached != null)
		{
			return cached;
		}

		Integer knownId = KNOWN_ITEM_IDS.get(normalized);
		if (knownId != null)
		{
			itemIdCache.put(normalized, knownId);
			return knownId;
		}

		if (itemManager != null)
		{
			try
			{
				List<ItemPrice> searchResults = itemManager.search(itemName);
				if (searchResults != null && !searchResults.isEmpty())
				{
					ItemPrice match = searchResults.stream()
						.filter(p -> p.getName().equalsIgnoreCase(itemName))
						.findFirst()
						.orElse(null);

					if (match != null && match.getId() > 0)
					{
						int itemId = match.getId();
						log.debug("Found item ID {} for '{}'", itemId, itemName);
						itemIdCache.put(normalized, itemId);
						return itemId;
					}
				}
			}
			catch (Exception e)
			{
				log.debug("Failed to look up item ID for '{}': {}", itemName, e.getMessage());
			}
		}

		return -1;
	}

	Map<String, Integer> getItemIdCache()
	{
		return itemIdCache;
	}

	Set<String> getPendingLookups()
	{
		return pendingLookups;
	}

	/**
	 * Get expected drop rate for an item from a specific shipwreck type
	 * Loads rates from drop_rates.json configuration file
	 */
	private double getExpectedDropRate(ShipwreckType shipwreckType, String itemName)
	{
		if (dropRateManager != null)
		{
			return dropRateManager.getExpectedDropRate(shipwreckType, itemName);
		}
		return 0.0;
	}


	private void resetShipwreckData(ShipwreckType type)
	{
		synchronized (this)
		{
			salvageDataMap.remove(type);
			saveSalvageData();
		}
		panel.updateData(salvageDataMap);
		panel.rebuild();
		log.info("Reset data for {}", type.getDisplayName());
	}

	private void resetAllData()
	{
		synchronized (this)
		{
			salvageDataMap.clear();
			saveSalvageData();
		}
		panel.updateData(salvageDataMap);
		panel.rebuild();
		log.info("Reset all salvage data");
	}

	@Provides
	SalvageSackConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SalvageSackConfig.class);
	}
}
