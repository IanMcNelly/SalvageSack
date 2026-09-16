package com.salvagesack;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages loading and accessing expected drop rates from configuration
 */
@Slf4j
public class DropRateManager
{
	private final Map<ShipwreckType, Map<String, Double>> dropRates = new HashMap<>();
	private final Map<ShipwreckType, Map<String, Double>> displayItemRates = new HashMap<>();
	private final Gson gson;
	private final File userConfigFile;
	private JsonObject bundledRoot;

	public DropRateManager(File dataDirectory, Gson gson)
	{
		this.gson = gson;
		this.userConfigFile = new File(dataDirectory, "drop_rates.json");
		loadDropRates();
	}

	/**
	 * Load drop rates from bundled resource and user config
	 */
	private void loadDropRates()
	{
		loadBundledRates();

		if (userConfigFile.exists())
		{
			try
			{
				migrateUserConfigIfNeeded();
			}
			catch (Exception e)
			{
				log.warn("Failed to migrate user drop rates config: {}", e.getMessage());
			}
			loadUserRates();
		}
		else
		{
			copyBundledToUser();
		}
	}

	/**
	 * Load rates from bundled resource
	 */
	private void loadBundledRates()
	{
		try (InputStream is = getClass().getResourceAsStream("/drop_rates.json"))
		{
			if (is != null)
			{
				try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8))
				{
					bundledRoot = gson.fromJson(reader, JsonObject.class);
					parseDropRatesFromObject(bundledRoot);
					log.info("Loaded bundled drop rates");
				}
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to load bundled drop rates: {}", e.getMessage());
		}
	}

	/**
	 * Migrate user configuration if it is from an older version than bundled defaults.
	 * Preserves intentional user customizations while refreshing default rates and adding new items.
	 */
	private void migrateUserConfigIfNeeded()
	{
		if (bundledRoot == null)
		{
			return;
		}

		int bundledVersion = bundledRoot.has("version") ? bundledRoot.get("version").getAsInt() : 1;
		JsonObject userRoot = null;
		try (InputStreamReader reader = new InputStreamReader(new FileInputStream(userConfigFile), StandardCharsets.UTF_8))
		{
			userRoot = gson.fromJson(reader, JsonObject.class);
		}
		catch (Exception e)
		{
			log.warn("Failed to read user drop rates config for migration check: {}", e.getMessage());
			return;
		}

		if (userRoot == null)
		{
			return;
		}

		int userVersion = userRoot.has("version") ? userRoot.get("version").getAsInt() : 1;
		if (userVersion >= bundledVersion)
		{
			return;
		}

		log.info("Migrating user drop rates from version {} to {}", userVersion, bundledVersion);

		backupUserConfig(userVersion);

		JsonObject v1Root = loadV1Rates();

		JsonObject migrated = mergeUserCustomizations(userRoot, v1Root, bundledRoot);

		try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(userConfigFile), StandardCharsets.UTF_8))
		{
			gson.toJson(migrated, writer);
			log.info("Successfully migrated user drop rates to version {}", bundledVersion);
		}
		catch (Exception e)
		{
			log.warn("Failed to save migrated drop rates config: {}", e.getMessage());
		}
	}

	/**
	 * Create backup files of the user configuration before migrating
	 */
	private void backupUserConfig(int oldVersion)
	{
		try
		{
			File parentDir = userConfigFile.getParentFile();
			if (parentDir != null && parentDir.exists())
			{
				File versionedBackup = new File(parentDir, "drop_rates.json.v" + oldVersion + ".bak");
				Files.copy(userConfigFile.toPath(), versionedBackup.toPath(), StandardCopyOption.REPLACE_EXISTING);
				File genericBackup = new File(parentDir, "drop_rates.json.bak");
				Files.copy(userConfigFile.toPath(), genericBackup.toPath(), StandardCopyOption.REPLACE_EXISTING);
				log.info("Created backup of user drop rates at {}", genericBackup.getAbsolutePath());
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to create backup of user drop rates: {}", e.getMessage());
		}
	}

	/**
	 * Load historical v1 rates from bundled resource to detect customizations
	 */
	private JsonObject loadV1Rates()
	{
		try (InputStream is = getClass().getResourceAsStream("/drop_rates_v1.json"))
		{
			if (is != null)
			{
				try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8))
				{
					return gson.fromJson(reader, JsonObject.class);
				}
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to load v1 reference drop rates: {}", e.getMessage());
		}
		return null;
	}

	/**
	 * Merge intentional user customizations onto new bundled defaults
	 */
	private JsonObject mergeUserCustomizations(JsonObject userRoot, JsonObject v1Root, JsonObject bundledRoot)
	{
		JsonObject migrated = bundledRoot.deepCopy();
		JsonObject userShipwrecks = userRoot.getAsJsonObject("shipwrecks");
		JsonObject v1Shipwrecks = v1Root != null ? v1Root.getAsJsonObject("shipwrecks") : null;
		JsonObject migratedShipwrecks = migrated.getAsJsonObject("shipwrecks");

		if (userShipwrecks != null && migratedShipwrecks != null)
		{
			for (String shipwreckKey : userShipwrecks.keySet())
			{
				JsonObject userSw = userShipwrecks.getAsJsonObject(shipwreckKey);
				JsonObject userItems = userSw != null ? userSw.getAsJsonObject("items") : null;
				if (userItems == null)
				{
					continue;
				}

				JsonObject v1Sw = v1Shipwrecks != null ? v1Shipwrecks.getAsJsonObject(shipwreckKey) : null;
				JsonObject v1Items = v1Sw != null ? v1Sw.getAsJsonObject("items") : null;

				JsonObject migratedSw = migratedShipwrecks.getAsJsonObject(shipwreckKey);
				if (migratedSw == null)
				{
					migratedShipwrecks.add(shipwreckKey, userSw.deepCopy());
					continue;
				}

				JsonObject migratedItems = migratedSw.getAsJsonObject("items");
				if (migratedItems == null)
				{
					migratedItems = new JsonObject();
					migratedSw.add("items", migratedItems);
				}

				for (String itemName : userItems.keySet())
				{
					JsonElement userRateEl = userItems.get(itemName);
					if (userRateEl == null || !userRateEl.isJsonPrimitive())
					{
						continue;
					}
					double userRate = userRateEl.getAsDouble();

					JsonElement v1RateEl = v1Items != null ? v1Items.get(itemName) : null;
					if (v1RateEl == null && v1Items != null)
					{
						if (itemName.contains(" (unf)"))
						{
							v1RateEl = v1Items.get(itemName.replace(" (unf)", "(unf)"));
						}
						else if (itemName.contains("(unf)"))
						{
							v1RateEl = v1Items.get(itemName.replace("(unf)", " (unf)"));
						}
					}

					String canonicalName = "Adamant bolts(unf)".equalsIgnoreCase(itemName) ? "Adamant bolts (unf)" : itemName;

					if (v1RateEl != null && v1RateEl.isJsonPrimitive())
					{
						double v1Rate = v1RateEl.getAsDouble();
						if (Math.abs(userRate - v1Rate) > 1e-9)
						{
							if (!canonicalName.equals(itemName))
							{
								migratedItems.remove(itemName);
							}
							migratedItems.addProperty(canonicalName, userRate);
							log.info("Preserving custom user drop rate for '{}' in {}: {}", canonicalName, shipwreckKey, userRate);
						}
					}
					else
					{
						if (!canonicalName.equals(itemName))
						{
							migratedItems.remove(itemName);
						}
						migratedItems.addProperty(canonicalName, userRate);
						log.info("Preserving custom user drop item '{}' in {}: {}", canonicalName, shipwreckKey, userRate);
					}
				}
			}
		}

		return migrated;
	}

	/**
	 * Load rates from user config file
	 */
	private void loadUserRates()
	{
		try (InputStreamReader reader = new InputStreamReader(new FileInputStream(userConfigFile), StandardCharsets.UTF_8))
		{
			parseDropRates(reader);
			log.info("Loaded user drop rates from {}", userConfigFile.getAbsolutePath());
		}
		catch (Exception e)
		{
			log.warn("Failed to load user drop rates: {}", e.getMessage());
		}
	}

	/**
	 * Copy bundled config to user directory
	 */
	private void copyBundledToUser()
	{
		try (InputStream is = getClass().getResourceAsStream("/drop_rates.json"))
		{
			if (is != null)
			{
				File parentDir = userConfigFile.getParentFile();
				if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs())
				{
					log.warn("Failed to create directory: {}", parentDir.getAbsolutePath());
					return;
				}
				try (FileOutputStream fos = new FileOutputStream(userConfigFile))
				{
					byte[] buffer = new byte[1024];
					int bytesRead;
					while ((bytesRead = is.read(buffer)) != -1)
					{
						fos.write(buffer, 0, bytesRead);
					}
				}
				log.info("Copied drop rates config to {}", userConfigFile.getAbsolutePath());
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to copy drop rates config: {}", e.getMessage());
		}
	}

	/**
	 * Parse drop rates from a reader
	 */
	private void parseDropRates(Reader reader)
	{
		JsonObject root = gson.fromJson(reader, JsonObject.class);
		parseDropRatesFromObject(root);
	}

	/**
	 * Parse drop rates from a JsonObject
	 */
	private void parseDropRatesFromObject(JsonObject root)
	{
		if (root == null)
		{
			return;
		}

		JsonObject shipwrecks = root.getAsJsonObject("shipwrecks");
		if (shipwrecks == null)
		{
			return;
		}

		for (String shipwreckKey : shipwrecks.keySet())
		{
			try
			{
				ShipwreckType type = ShipwreckType.valueOf(shipwreckKey);
				JsonObject shipwreckData = shipwrecks.getAsJsonObject(shipwreckKey);
				JsonObject items = shipwreckData.getAsJsonObject("items");

				if (items != null)
				{
					Map<String, Double> itemRates = dropRates.computeIfAbsent(type, k -> new HashMap<>());
					Map<String, Double> displayRates = displayItemRates.computeIfAbsent(type, k -> new LinkedHashMap<>());

					Map<String, Double> sourceRates = new HashMap<>();
					for (String itemName : items.keySet())
					{
						JsonElement rateElement = items.get(itemName);
						if (rateElement != null && rateElement.isJsonPrimitive())
						{
							sourceRates.put(itemName.toLowerCase().trim(), rateElement.getAsDouble());
							displayRates.put(itemName, rateElement.getAsDouble());
						}
					}

					for (Map.Entry<String, Double> entry : sourceRates.entrySet())
					{
						String lowerKey = entry.getKey();
						double rate = entry.getValue();
						
						itemRates.put(lowerKey, rate);
						
						if (lowerKey.contains(" (unf)"))
						{
							String alias = lowerKey.replace(" (unf)", "(unf)");
							if (!sourceRates.containsKey(alias))
							{
								itemRates.put(alias, rate);
							}
						}
						else if (lowerKey.contains("(unf)"))
						{
							String alias = lowerKey.replace("(unf)", " (unf)");
							if (!sourceRates.containsKey(alias))
							{
								itemRates.put(alias, rate);
							}
						}
					}
				}
			}
			catch (IllegalArgumentException e)
			{
				log.debug("Unknown shipwreck type in config: {}", shipwreckKey);
			}
		}
	}

	/**
	 * Get the expected drop rate for an item from a specific shipwreck type
	 * @param shipwreckType The type of shipwreck
	 * @param itemName The name of the item
	 * @return The expected drop rate (0.0 to 1.0), or 0.0 if unknown
	 */
	public double getExpectedDropRate(ShipwreckType shipwreckType, String itemName)
	{
		if (itemName == null)
		{
			return 0.0;
		}

		Map<String, Double> itemRates = dropRates.get(shipwreckType);
		if (itemRates == null)
		{
			log.debug("No rates found for shipwreck type: {}", shipwreckType);
			return 0.0;
		}

		String key = itemName.toLowerCase().trim();
		Double rate = itemRates.get(key);
		if (rate == null)
		{
			if (key.contains(" (unf)"))
			{
				rate = itemRates.get(key.replace(" (unf)", "(unf)"));
			}
			else if (key.contains("(unf)"))
			{
				rate = itemRates.get(key.replace("(unf)", " (unf)"));
			}
		}

		double result = rate != null ? rate : 0.0;
		return result;
	}

	/**
	 * Get all expected drop rates with original display item names for a shipwreck type
	 * @param shipwreckType The type of shipwreck
	 * @return An unmodifiable map of item name -> expected drop rate
	 */
	public Map<String, Double> getAllItemRates(ShipwreckType shipwreckType)
	{
		Map<String, Double> rates = displayItemRates.get(shipwreckType);
		if (rates == null)
		{
			return Collections.emptyMap();
		}
		return Collections.unmodifiableMap(rates);
	}
}

