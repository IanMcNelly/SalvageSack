package com.salvagesack;

import com.google.gson.Gson;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class SalvageDataManagerTest
{
	@Test
	public void testSerializationRoundTrip() throws Exception
	{
		Gson gson = new Gson();

		// Create data with lastUpdated
		Map<ShipwreckType, SalvageData> dataMap = new HashMap<>();
		SalvageData smallData = new SalvageData(ShipwreckType.SMALL);
		smallData.incrementTotalLoots();
		smallData.incrementTotalLoots();
		smallData.setLastUpdated(1700000000123L);
		smallData.recordLoot(31989, "Boat bottle (empty)", 0.00133333, 2);

		dataMap.put(ShipwreckType.SMALL, smallData);

		// Use SalvageDataManager's private methods via reflection to test exact serialization
		SalvageDataManager manager = new SalvageDataManager(null, null, gson);

		Method parseJsonMethod = SalvageDataManager.class.getDeclaredMethod("parseJsonData", String.class);
		parseJsonMethod.setAccessible(true);

		// Convert dataMap to json manually like saveData does
		Class<?> wrapperClass = Class.forName("com.salvagesack.SalvageDataManager$SaveDataWrapper");
		Class<?> dtoClass = Class.forName("com.salvagesack.SalvageDataManager$SalvageDataDto");

		java.lang.reflect.Constructor<?> wrapperConstructor = wrapperClass.getDeclaredConstructor();
		wrapperConstructor.setAccessible(true);
		Object wrapper = wrapperConstructor.newInstance();

		java.lang.reflect.Field shipwrecksField = wrapperClass.getDeclaredField("shipwrecks");
		shipwrecksField.setAccessible(true);
		Map<String, Object> shipwrecks = new HashMap<>();
		shipwrecksField.set(wrapper, shipwrecks);

		Method fromSalvageDataMethod = dtoClass.getDeclaredMethod("fromSalvageData", SalvageData.class);
		fromSalvageDataMethod.setAccessible(true);
		Object dto = fromSalvageDataMethod.invoke(null, smallData);
		shipwrecks.put(ShipwreckType.SMALL.name(), dto);

		String json = gson.toJson(wrapper);
		assertNotNull(json);
		assertTrue(json.contains("1700000000123"));

		@SuppressWarnings("unchecked")
		Map<ShipwreckType, SalvageData> loaded = (Map<ShipwreckType, SalvageData>) parseJsonMethod.invoke(manager, json);

		assertNotNull(loaded);
		assertEquals(1, loaded.size());
		SalvageData loadedSmall = loaded.get(ShipwreckType.SMALL);
		assertNotNull(loadedSmall);
		assertEquals(2, loadedSmall.getTotalLoots());
		assertEquals(1700000000123L, loadedSmall.getLastUpdated());

		SalvageItem item = loadedSmall.getItems().get(31989);
		assertNotNull(item);
		assertEquals("Boat bottle (empty)", item.getItemName());
		assertEquals(1, item.getDropCount());
		assertEquals(2, item.getTotalQuantity());
		assertEquals(0.00133333, item.getExpectedDropRate(), 0.000001);
	}
}
