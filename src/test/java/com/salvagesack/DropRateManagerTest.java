package com.salvagesack;

import com.google.gson.Gson;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Map;

import static org.junit.Assert.*;

public class DropRateManagerTest
{
	private DropRateManager dropRateManager;

	@Before
	public void setUp()
	{
		// Use a non-existent temp dir so it loads bundled drop rates without user overrides
		File tempDir = new File(System.getProperty("java.io.tmpdir"), "salvagesack_test_" + System.currentTimeMillis());
		dropRateManager = new DropRateManager(tempDir, new Gson());
	}

	@Test
	public void testAllShipwrecksHaveDropRates()
	{
		for (ShipwreckType type : ShipwreckType.values())
		{
			if (type == ShipwreckType.UNKNOWN)
			{
				continue;
			}
			Map<String, Double> rates = dropRateManager.getAllItemRates(type);
			assertNotNull("Rates should not be null for " + type, rates);
			assertFalse("Rates should not be empty for " + type, rates.isEmpty());
		}
	}

	@Test
	public void testBundledDropRatesItemCounts()
	{
		assertEquals(23, dropRateManager.getAllItemRates(ShipwreckType.SMALL).size());
		assertEquals(25, dropRateManager.getAllItemRates(ShipwreckType.FISHERMANS).size());
		assertEquals(23, dropRateManager.getAllItemRates(ShipwreckType.BARRACUDA).size());
		assertEquals(23, dropRateManager.getAllItemRates(ShipwreckType.LARGE).size());
		assertEquals(21, dropRateManager.getAllItemRates(ShipwreckType.PIRATE).size());
		assertEquals(28, dropRateManager.getAllItemRates(ShipwreckType.MERCENARY).size());
		assertEquals(27, dropRateManager.getAllItemRates(ShipwreckType.FREMENNIK).size());
		assertEquals(55, dropRateManager.getAllItemRates(ShipwreckType.MERCHANT).size());
	}

	@Test
	public void testSpecificDropRates()
	{
		// Small salvage: Boat bottle (empty) 1/750 (~0.00133333)
		assertEquals(0.00133333, dropRateManager.getExpectedDropRate(ShipwreckType.SMALL, "Boat bottle (empty)"), 0.00001);
		// Small salvage: Soup pet 1/800,000 (0.00000125)
		assertEquals(0.00000125, dropRateManager.getExpectedDropRate(ShipwreckType.SMALL, "Soup"), 0.00000001);

		// Fishermans salvage: Clue scroll (easy)
		assertEquals(0.00333333, dropRateManager.getExpectedDropRate(ShipwreckType.FISHERMANS, "Clue scroll (easy)"), 0.00001);

		// Merchant salvage: Dragon cannon barrel (1/20,000 = 0.00005)
		assertEquals(0.00005, dropRateManager.getExpectedDropRate(ShipwreckType.MERCHANT, "Dragon cannon barrel"), 0.000001);

		// Unknown item returns 0.0
		assertEquals(0.0, dropRateManager.getExpectedDropRate(ShipwreckType.SMALL, "Nonexistent Item"), 0.001);
		assertEquals(0.0, dropRateManager.getExpectedDropRate(ShipwreckType.SMALL, null), 0.001);

		// Trimming and case-insensitivity
		assertEquals(0.00133333, dropRateManager.getExpectedDropRate(ShipwreckType.SMALL, "  boat bottle (empty)  "), 0.00001);

		// Bolt name variants: "Adamant bolts(unf)" vs "Adamant bolts (unf)" in Mercenary salvage
		double expectedBoltRate = 0.04098361;
		assertEquals(expectedBoltRate, dropRateManager.getExpectedDropRate(ShipwreckType.MERCENARY, "Adamant bolts(unf)"), 0.000001);
		assertEquals(expectedBoltRate, dropRateManager.getExpectedDropRate(ShipwreckType.MERCENARY, "Adamant bolts (unf)"), 0.000001);

		// Canonical display name must have space
		assertTrue(dropRateManager.getAllItemRates(ShipwreckType.MERCENARY).containsKey("Adamant bolts (unf)"));
		assertFalse(dropRateManager.getAllItemRates(ShipwreckType.MERCENARY).containsKey("Adamant bolts(unf)"));
	}

	@Test
	public void testV1MigrationUncustomized() throws Exception
	{
		Gson gson = new Gson();
		File tempDir = new File(System.getProperty("java.io.tmpdir"), "salvagesack_v1_uncust_" + System.currentTimeMillis());
		tempDir.mkdirs();
		tempDir.deleteOnExit();

		File userFile = new File(tempDir, "drop_rates.json");
		// Copy v1 resource to user file location
		try (java.io.InputStream is = getClass().getResourceAsStream("/drop_rates_v1.json");
		     java.io.FileOutputStream fos = new java.io.FileOutputStream(userFile))
		{
			byte[] buf = new byte[1024];
			int r;
			while ((r = is.read(buf)) != -1)
			{
				fos.write(buf, 0, r);
			}
		}

		DropRateManager mgr = new DropRateManager(tempDir, gson);

		// Verify rates were upgraded to v2
		assertEquals(0.04098361, mgr.getExpectedDropRate(ShipwreckType.MERCENARY, "Adamant bolts (unf)"), 0.000001);
		// New v2 item should be present
		assertTrue(mgr.getExpectedDropRate(ShipwreckType.MERCENARY, "Facility bottle (empty)") > 0);

		// Verify backups were created
		File genericBackup = new File(tempDir, "drop_rates.json.bak");
		File versionedBackup = new File(tempDir, "drop_rates.json.v1.bak");
		assertTrue("Backup file should exist", genericBackup.exists());
		assertTrue("Versioned backup file should exist", versionedBackup.exists());
	}

	@Test
	public void testV1MigrationWithUserCustomizations() throws Exception
	{
		Gson gson = new Gson();
		File tempDir = new File(System.getProperty("java.io.tmpdir"), "salvagesack_v1_cust_" + System.currentTimeMillis());
		tempDir.mkdirs();
		tempDir.deleteOnExit();

		// Load v1 resource and modify one item rate
		com.google.gson.JsonObject v1Root;
		try (java.io.InputStream is = getClass().getResourceAsStream("/drop_rates_v1.json");
		     java.io.InputStreamReader reader = new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))
		{
			v1Root = gson.fromJson(reader, com.google.gson.JsonObject.class);
		}
		// Custom rate for Soup in SMALL: 0.5 (was 0.00000125 in v1)
		v1Root.getAsJsonObject("shipwrecks")
			.getAsJsonObject("SMALL")
			.getAsJsonObject("items")
			.addProperty("Soup", 0.5);

		File userFile = new File(tempDir, "drop_rates.json");
		try (java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(new java.io.FileOutputStream(userFile), java.nio.charset.StandardCharsets.UTF_8))
		{
			gson.toJson(v1Root, writer);
		}

		DropRateManager mgr = new DropRateManager(tempDir, gson);

		// Custom edit should be PRESERVED
		assertEquals(0.5, mgr.getExpectedDropRate(ShipwreckType.SMALL, "Soup"), 0.000001);
		// Uncustomized items should be upgraded to v2 rates
		assertEquals(0.04098361, mgr.getExpectedDropRate(ShipwreckType.MERCENARY, "Adamant bolts (unf)"), 0.000001);
		// New v2 items should be added
		assertTrue(mgr.getExpectedDropRate(ShipwreckType.MERCENARY, "Facility bottle (empty)") > 0);
	}

	@Test
	public void testMigrationPreservesLegacyUnfCustomizationUnderCanonicalName() throws Exception
	{
		Gson gson = new Gson();
		File tempDir = new File(System.getProperty("java.io.tmpdir"), "salvagesack_v1_bolts_" + System.currentTimeMillis());
		tempDir.mkdirs();
		tempDir.deleteOnExit();

		// Load v1 resource and customize the legacy "Adamant bolts(unf)" key
		com.google.gson.JsonObject v1Root;
		try (java.io.InputStream is = getClass().getResourceAsStream("/drop_rates_v1.json");
		     java.io.InputStreamReader reader = new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))
		{
			v1Root = gson.fromJson(reader, com.google.gson.JsonObject.class);
		}
		// In v1, it was "Adamant bolts(unf)": 0.0412. Customize it to 0.08:
		v1Root.getAsJsonObject("shipwrecks")
			.getAsJsonObject("MERCENARY")
			.getAsJsonObject("items")
			.addProperty("Adamant bolts(unf)", 0.08);

		File userFile = new File(tempDir, "drop_rates.json");
		try (java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(new java.io.FileOutputStream(userFile), java.nio.charset.StandardCharsets.UTF_8))
		{
			gson.toJson(v1Root, writer);
		}

		DropRateManager mgr = new DropRateManager(tempDir, gson);

		// Custom rate must be accessible under canonical name
		assertEquals(0.08, mgr.getExpectedDropRate(ShipwreckType.MERCENARY, "Adamant bolts (unf)"), 0.000001);
		// getAllItemRates must contain canonical name, NOT duplicate legacy alias
		Map<String, Double> rates = mgr.getAllItemRates(ShipwreckType.MERCENARY);
		assertTrue("Should contain canonical key", rates.containsKey("Adamant bolts (unf)"));
		assertEquals(0.08, rates.get("Adamant bolts (unf)"), 0.000001);
		assertFalse("Should not contain legacy alias key", rates.containsKey("Adamant bolts(unf)"));
	}
}
