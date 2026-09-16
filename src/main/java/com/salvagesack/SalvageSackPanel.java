package com.salvagesack;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Panel that displays salvage tracking information
 * <p>
 * The panel includes sorting controls that allow users to organize items
 * by different criteria including alphabetical order, current drop rate,
 * expected drop rate, quantity, and luck (based on rate comparison).
 * Sort direction can be toggled between ascending and descending.
 * </p>
 * <p>
 * Luck sorting orders items by comparing current drop rate to expected rate:
 * <ul>
 *   <li>Green (lucky) - receiving items more often than expected (ratio ≥ 1.1)</li>
 *   <li>Yellow (neutral) - receiving items at expected rate (0.9 ≤ ratio < 1.1)</li>
 *   <li>Red (unlucky) - receiving items less often than expected (ratio < 0.9)</li>
 *   <li>Unknown - no expected rate data available</li>
 * </ul>
 * Descending order shows: Green → Yellow → Red → Unknown<br>
 * Ascending order shows: Red → Yellow → Green → Unknown
 * </p>
 */
@Slf4j
public class SalvageSackPanel extends PluginPanel
{
	private static final String CONFIG_GROUP = "salvagesack";
	private static final Color LUCK_GOOD = new Color(0, 200, 83);     
	private static final Color LUCK_NEUTRAL = new Color(255, 214, 0);
	private static final Color LUCK_BAD = new Color(255, 68, 68);    
	private static final String ARROW_RIGHT = "▶";
	private static final String ARROW_DOWN = "▼";

	private final JPanel contentPanel;
	private final ItemIconManager iconManager;
	private final SalvageSackConfig config;
	private final Map<ShipwreckType, Boolean> expandedState = new ConcurrentHashMap<>();
	private final JLabel totalOpensLabel;
	private final JComboBox<SortOption> sortComboBox;
	private final JButton sortDirectionButton;
	private Map<ShipwreckType, SalvageData> salvageDataMap;
	private SortOption currentSortOption;
	private boolean currentSortDescending;

	@lombok.Setter
	private DropRateManager dropRateManager;

	@lombok.Setter
	private Consumer<ShipwreckType> onResetShipwreck;

	@lombok.Setter
	private Runnable onResetAll;

	private ConfigManager configManager;

	public void setConfigManager(ConfigManager configManager)
	{
		this.configManager = configManager;
		loadExpandedStates();
	}

	private void loadExpandedStates()
	{
		if (configManager == null)
		{
			return;
		}

		for (ShipwreckType type : ShipwreckType.values())
		{
			if (type != ShipwreckType.UNKNOWN)
			{
				String val = configManager.getConfiguration(CONFIG_GROUP, "expanded_" + type.name());
				if (val != null)
				{
					expandedState.put(type, Boolean.parseBoolean(val));
				}
			}
		}
	}

	public boolean isExpanded(ShipwreckType type)
	{
		SalvageData data = salvageDataMap != null ? salvageDataMap.get(type) : null;
		boolean hasData = data != null && data.getTotalLoots() > 0;
		return expandedState.getOrDefault(type, hasData);
	}

	public void setExpanded(ShipwreckType type, boolean expanded)
	{
		expandedState.put(type, expanded);
		if (configManager != null)
		{
			configManager.setConfiguration(CONFIG_GROUP, "expanded_" + type.name(), expanded);
		}
	}

	@lombok.Setter
	private Function<String, Integer> itemIdLookup;

	public SalvageSackPanel(ItemIconManager iconManager, SalvageSackConfig config)
	{
		super(false);
		this.iconManager = iconManager;
		this.config = config;
		this.currentSortOption = config.sortOption();
		this.currentSortDescending = config.sortDescending();
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setLayout(new BorderLayout());

		JPanel titlePanel = new JPanel(new BorderLayout());
		titlePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		titlePanel.setBorder(new EmptyBorder(10, 10, 10, 10));

		JPanel infoPanel = new JPanel(new GridBagLayout());
		infoPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.weightx = 1.0;
		gbc.anchor = GridBagConstraints.CENTER;

		totalOpensLabel = new JLabel("0 Total Salvage Sorted", SwingConstants.CENTER);
		totalOpensLabel.setForeground(Color.WHITE);
		totalOpensLabel.setFont(new Font("Arial", Font.BOLD, 12));

		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.gridwidth = 2;
		gbc.insets = new Insets(0, 0, 8, 0);
		infoPanel.add(totalOpensLabel, gbc);
		
		sortComboBox = new JComboBox<>(SortOption.values());
		sortComboBox.setSelectedItem(currentSortOption);
		sortComboBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		sortComboBox.setForeground(Color.WHITE);
		sortComboBox.setFont(new Font("Arial", Font.PLAIN, 11));
		sortComboBox.setFocusable(false);
		sortComboBox.setBorder(new CompoundBorder(
			new LineBorder(ColorScheme.MEDIUM_GRAY_COLOR, 1),
			new EmptyBorder(4, 8, 4, 8)
		));
		sortComboBox.addActionListener(e -> {
			SortOption selected = (SortOption) sortComboBox.getSelectedItem();
			if (selected != null && selected != currentSortOption)
			{
				currentSortOption = selected;
				if (configManager != null)
				{
					configManager.setConfiguration(CONFIG_GROUP, "sortOption", selected);
				}
				rebuild();
			}
		});
		
		gbc.gridx = 0;
		gbc.gridy = 1;
		gbc.gridwidth = 1;
		gbc.weightx = 1.0;
		gbc.insets = new Insets(0, 0, 0, 4);
		infoPanel.add(sortComboBox, gbc);
		
		sortDirectionButton = new JButton(currentSortDescending ? "↓" : "↑");
		sortDirectionButton.setFont(new Font("Arial", Font.BOLD, 16));
		sortDirectionButton.setPreferredSize(new Dimension(40, 32));
		sortDirectionButton.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		sortDirectionButton.setForeground(Color.WHITE);
		sortDirectionButton.setFocusable(false);
		sortDirectionButton.setBorder(new LineBorder(ColorScheme.MEDIUM_GRAY_COLOR, 1));
		sortDirectionButton.setToolTipText(currentSortDescending ? "Descending" : "Ascending");
		sortDirectionButton.addActionListener(e -> {
			currentSortDescending = !currentSortDescending;
			sortDirectionButton.setText(currentSortDescending ? "↓" : "↑");
			sortDirectionButton.setToolTipText(currentSortDescending ? "Descending" : "Ascending");
			if (configManager != null)
			{
				configManager.setConfiguration(CONFIG_GROUP, "sortDescending", currentSortDescending);
			}
			rebuild();
		});
		sortDirectionButton.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseEntered(MouseEvent e) {
				sortDirectionButton.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
			}
			
			@Override
			public void mouseExited(MouseEvent e) {
				sortDirectionButton.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});
		
		gbc.gridx = 1;
		gbc.gridy = 1;
		gbc.weightx = 0.0;
		gbc.insets = new Insets(0, 0, 0, 0);
		infoPanel.add(sortDirectionButton, gbc);
		
		titlePanel.add(infoPanel, BorderLayout.CENTER);

		add(titlePanel, BorderLayout.NORTH);

		contentPanel = new JPanel();
		contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
		contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		contentPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

		JScrollPane scrollPane = new JScrollPane(contentPanel);
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.setBorder(null);
		scrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
		scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);

		add(scrollPane, BorderLayout.CENTER);

		rebuild();
	}

	public void onConfigChanged()
	{
		this.currentSortOption = config.sortOption();
		this.currentSortDescending = config.sortDescending();
		SwingUtilities.invokeLater(() -> {
			if (sortComboBox != null && sortComboBox.getSelectedItem() != currentSortOption)
			{
				sortComboBox.setSelectedItem(currentSortOption);
			}
			if (sortDirectionButton != null)
			{
				sortDirectionButton.setText(currentSortDescending ? "↓" : "↑");
				sortDirectionButton.setToolTipText(currentSortDescending ? "Descending" : "Ascending");
			}
			rebuild();
		});
	}

	public void updateData(Map<ShipwreckType, SalvageData> dataMap)
	{
		this.salvageDataMap = dataMap;

		if (dataMap != null)
		{
			for (Map.Entry<ShipwreckType, SalvageData> entry : dataMap.entrySet())
			{
				ShipwreckType type = entry.getKey();
				if (type != ShipwreckType.UNKNOWN && !expandedState.containsKey(type))
				{
					if (configManager != null)
					{
						String val = configManager.getConfiguration(CONFIG_GROUP, "expanded_" + type.name());
						if (val != null)
						{
							expandedState.put(type, Boolean.parseBoolean(val));
							continue;
						}
					}

					SalvageData data = entry.getValue();
					if (data != null && data.getTotalLoots() > 0)
					{
						expandedState.put(type, true);
					}
				}
			}
		}
	}

	public void rebuild()
	{
		SwingUtilities.invokeLater(() -> {
			contentPanel.removeAll();

			int totalOpens = 0;
			if (salvageDataMap != null)
			{
				for (SalvageData data : salvageDataMap.values())
				{
					totalOpens += data.getTotalLoots();
				}
			}
			totalOpensLabel.setText(totalOpens + " Total Salvage Sorted");

			List<ShipwreckType> activeTypes = new ArrayList<>();
			List<ShipwreckType> inactiveTypes = new ArrayList<>();

			for (ShipwreckType type : ShipwreckType.values())
			{
				if (type == ShipwreckType.UNKNOWN)
				{
					continue;
				}

				SalvageData data = salvageDataMap != null ? salvageDataMap.get(type) : null;
				if (data != null && data.getTotalLoots() > 0)
				{
					activeTypes.add(type);
				}
				else
				{
					inactiveTypes.add(type);
				}
			}

			activeTypes.sort((t1, t2) -> {
				SalvageData d1 = salvageDataMap != null ? salvageDataMap.get(t1) : null;
				SalvageData d2 = salvageDataMap != null ? salvageDataMap.get(t2) : null;
				long time1 = d1 != null ? d1.getLastUpdated() : 0;
				long time2 = d2 != null ? d2.getLastUpdated() : 0;
				return Long.compare(time2, time1);
			});

			for (ShipwreckType type : activeTypes)
			{
				SalvageData data = salvageDataMap != null ? salvageDataMap.get(type) : null;
				if (data != null)
				{
					contentPanel.add(createShipwreckPanel(type, data));
					contentPanel.add(Box.createVerticalStrut(4));
				}
			}

			for (ShipwreckType type : inactiveTypes)
			{
				SalvageData data = salvageDataMap != null ? salvageDataMap.get(type) : null;
				contentPanel.add(createShipwreckPanel(type, data));
				contentPanel.add(Box.createVerticalStrut(4));
			}

			contentPanel.revalidate();
			contentPanel.repaint();
		});
	}

	private JPanel createShipwreckPanel(ShipwreckType type, SalvageData data)
	{
		int totalLoots = data != null ? data.getTotalLoots() : 0;
		boolean hasData = totalLoots > 0;
		boolean isExpanded = expandedState.getOrDefault(type, hasData);

		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new LineBorder(ColorScheme.MEDIUM_GRAY_COLOR, 1));
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel headerPanel = new JPanel(new BorderLayout(6, 0));
		headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		headerPanel.setBorder(new EmptyBorder(4, 6, 4, 6));
		headerPanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		headerPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

		JLabel arrowLabel = new JLabel(isExpanded ? ARROW_DOWN : ARROW_RIGHT);
		arrowLabel.setForeground(Color.LIGHT_GRAY);
		arrowLabel.setFont(new Font("Arial", Font.PLAIN, 10));

		JLabel typeLabel = new JLabel(type.getDisplayName());
		typeLabel.setForeground(hasData ? Color.WHITE : new Color(180, 180, 180));
		typeLabel.setFont(new Font("Arial", Font.BOLD, 12));

		JLabel totalLabel = new JLabel(hasData ? "Sorts: " + totalLoots : "(0 sorts)");
		totalLabel.setForeground(hasData ? Color.LIGHT_GRAY : Color.GRAY);
		totalLabel.setFont(new Font("Arial", Font.PLAIN, 11));

		JPanel leftHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		leftHeader.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		leftHeader.add(arrowLabel);
		leftHeader.add(typeLabel);

		headerPanel.add(leftHeader, BorderLayout.WEST);
		headerPanel.add(totalLabel, BorderLayout.EAST);

		JPanel itemsPanel = new JPanel();
		itemsPanel.setLayout(new BoxLayout(itemsPanel, BoxLayout.Y_AXIS));
		itemsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		itemsPanel.setBorder(new EmptyBorder(2, 4, 4, 4));
		itemsPanel.setVisible(isExpanded);

		List<SalvageItem> sortedItems = getSortedItems(type, data);
		if (sortedItems.isEmpty())
		{
			JLabel emptyLabel = new JLabel("No item data available");
			emptyLabel.setForeground(Color.GRAY);
			emptyLabel.setFont(new Font("Arial", Font.ITALIC, 11));
			emptyLabel.setBorder(new EmptyBorder(4, 6, 4, 6));
			itemsPanel.add(emptyLabel);
		}
		else
		{
			for (SalvageItem item : sortedItems)
			{
				itemsPanel.add(createItemPanel(item, totalLoots, type));
				itemsPanel.add(Box.createVerticalStrut(2));
			}
		}

		headerPanel.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e))
				{
					boolean current = expandedState.getOrDefault(type, hasData);
					boolean newState = !current;
					setExpanded(type, newState);
					arrowLabel.setText(newState ? ARROW_DOWN : ARROW_RIGHT);
					itemsPanel.setVisible(newState);

					contentPanel.revalidate();
					contentPanel.repaint();
				}
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				if (SwingUtilities.isRightMouseButton(e) && hasData)
				{
					showContextMenu(e, type);
				}
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				headerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
				leftHeader.setBackground(ColorScheme.DARK_GRAY_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				leftHeader.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});

		panel.add(headerPanel);
		panel.add(itemsPanel);

		return panel;
	}

	private JPanel createItemPanel(SalvageItem item, int totalLoots, ShipwreckType shipwreckType)
	{
		double expectedRate = getExpectedRate(shipwreckType, item);
		boolean isUnobtained = item.getDropCount() == 0;
		double currentRate = item.getCurrentDropRate(totalLoots);
		boolean useOneInX = config.displayRateAs1inX();

		JPanel panel = new JPanel(new BorderLayout(6, 0));
		if (isUnobtained)
		{
			panel.setBackground(new Color(28, 28, 28));
			panel.setBorder(new CompoundBorder(
				new LineBorder(new Color(45, 45, 45), 1),
				new EmptyBorder(4, 6, 4, 6)
			));
		}
		else
		{
			panel.setBackground(new Color(40, 40, 40));
			panel.setBorder(new CompoundBorder(
				new LineBorder(new Color(60, 60, 60), 1),
				new EmptyBorder(4, 6, 4, 6)
			));
		}

		int panelHeight = expectedRate > 0 ? 54 : 44;
		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panelHeight));
		panel.setPreferredSize(new Dimension(0, panelHeight));

		BufferedImage icon = iconManager.getItemIcon(item.getItemId());
		JLabel iconLabel = new JLabel();
		if (icon != null)
		{
			iconLabel.setIcon(new ImageIcon(icon));
		}
		iconLabel.setPreferredSize(new Dimension(32, 32));
		iconLabel.setMinimumSize(new Dimension(32, 32));

		JPanel infoPanel = new JPanel();
		infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
		infoPanel.setBackground(panel.getBackground());

		JLabel nameLabel = new JLabel(item.getItemName());
		if (isUnobtained)
		{
			nameLabel.setForeground(new Color(150, 150, 150));
			nameLabel.setFont(new Font("Arial", Font.ITALIC, 11));
		}
		else
		{
			nameLabel.setForeground(Color.WHITE);
			nameLabel.setFont(new Font("Arial", Font.PLAIN, 11));
		}
		nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

		Color luckColor;
		if (isUnobtained)
		{
			if (totalLoots > 0 && expectedRate > 0)
			{
				double expectedDrops = totalLoots * expectedRate;
				if (expectedDrops >= 2.0)
				{
					luckColor = LUCK_BAD;
				}
				else if (expectedDrops >= 1.0)
				{
					luckColor = interpolateColor(LUCK_BAD, LUCK_NEUTRAL, 0.5f);
				}
				else
				{
					luckColor = new Color(130, 130, 130);
				}
			}
			else
			{
				luckColor = new Color(130, 130, 130);
			}
		}
		else
		{
			luckColor = getLuckColor(currentRate, expectedRate);
		}

		String currentRateText;
		if (isUnobtained)
		{
			currentRateText = totalLoots > 0 ? "Current: 0" : "Current: -";
		}
		else
		{
			currentRateText = "Current: " + formatRate(currentRate, useOneInX);
		}
		JLabel currentLabel = new JLabel(currentRateText);
		currentLabel.setForeground(luckColor);
		currentLabel.setFont(new Font("Arial", Font.BOLD, 9));
		currentLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

		infoPanel.add(nameLabel);
		infoPanel.add(currentLabel);

		if (expectedRate > 0)
		{
			JLabel expectedLabel = new JLabel("Expected: " + formatRate(expectedRate, useOneInX));
			expectedLabel.setForeground(isUnobtained ? new Color(120, 120, 120) : Color.LIGHT_GRAY);
			expectedLabel.setFont(new Font("Arial", Font.PLAIN, 9));
			expectedLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
			infoPanel.add(expectedLabel);
		}

		JLabel countLabel = new JLabel("x" + item.getTotalQuantity());
		countLabel.setForeground(isUnobtained ? new Color(100, 100, 100) : Color.WHITE);
		countLabel.setFont(new Font("Arial", Font.BOLD, 11));

		panel.add(iconLabel, BorderLayout.WEST);
		panel.add(infoPanel, BorderLayout.CENTER);
		panel.add(countLabel, BorderLayout.EAST);

		StringBuilder tooltip = new StringBuilder("<html><body style='padding: 4px;'>");
		tooltip.append("<b>").append(item.getItemName()).append("</b><br>");
		if (isUnobtained)
		{
			tooltip.append("<span style='color: #AAAAAA;'>Unobtained drop</span><br>");
			if (totalLoots > 0 && expectedRate > 0)
			{
				double expectedDrops = totalLoots * expectedRate;
				tooltip.append(String.format("0 drops in %,d sorts<br>", totalLoots));
				tooltip.append(String.format("Expected so far: ~%.2f drops<br>", expectedDrops));
				if (expectedDrops >= 1.0)
				{
					tooltip.append(String.format("<span style='color: #FF6666;'>Running dry (%.1fx rate without drop)</span><br>", expectedDrops));
				}
			}
		}
		else
		{
			tooltip.append(String.format("Dropped %d times (%d total quantity)<br>", item.getDropCount(), item.getTotalQuantity()));
			tooltip.append(String.format("Total sorts: %,d<br>", totalLoots));
			if (useOneInX)
			{
				tooltip.append(String.format("Actual rate: %s (%.3f%%)<br>", formatRate(currentRate, true), currentRate * 100));
			}
			else
			{
				tooltip.append(String.format("Actual rate: %.3f%%<br>", currentRate * 100));
			}
			if (expectedRate > 0)
			{
				double ratio = currentRate / expectedRate;
				if (useOneInX)
				{
					tooltip.append(String.format("Expected rate: %s (%.3f%%)<br>", formatRate(expectedRate, true), expectedRate * 100));
				}
				else
				{
					tooltip.append(String.format("Expected rate: %.3f%%<br>", expectedRate * 100));
				}
				tooltip.append(String.format("Expected drops: ~%.1f (Luck: %.2fx)<br>", totalLoots * expectedRate, ratio));
			}
		}
		if (expectedRate > 0)
		{
			tooltip.append(String.format("Base drop rarity: %s", formatRate(expectedRate, useOneInX)));
		}
		tooltip.append("</body></html>");

		applyTooltipRecursively(panel, tooltip.toString());

		return panel;
	}

	private void applyTooltipRecursively(Component comp, String tooltip)
	{
		if (comp instanceof JComponent)
		{
			((JComponent) comp).setToolTipText(tooltip);
		}
		if (comp instanceof Container)
		{
			for (Component child : ((Container) comp).getComponents())
			{
				applyTooltipRecursively(child, tooltip);
			}
		}
	}

	private static String normalizeItemName(String name)
	{
		if (name == null)
		{
			return "";
		}
		String normalized = name.toLowerCase().trim();
		if (normalized.contains(" (unf)"))
		{
			normalized = normalized.replace(" (unf)", "(unf)");
		}
		return normalized;
	}

	/**
	 * Returns a sorted list of SalvageItems based on the current sort option and direction.
	 */
	List<SalvageItem> getSortedItems(ShipwreckType shipwreckType, SalvageData data)
	{
		List<SalvageItem> items = new ArrayList<>();
		Set<String> obtainedNames = new HashSet<>();
		Set<Integer> obtainedIds = new HashSet<>();

		if (data != null && data.getItems() != null)
		{
			for (SalvageItem item : data.getItems().values())
			{
				items.add(item);
				obtainedNames.add(normalizeItemName(item.getItemName()));
				if (item.getItemId() > 0)
				{
					obtainedIds.add(item.getItemId());
				}
			}
		}

		boolean showUnobtained = config.showUnobtainedDrops() || (data == null || data.getTotalLoots() == 0);
		if (showUnobtained && dropRateManager != null)
		{
			Map<String, Double> allExpected = dropRateManager.getAllItemRates(shipwreckType);
			for (Map.Entry<String, Double> entry : allExpected.entrySet())
			{
				String itemName = entry.getKey();
				String normalizedName = normalizeItemName(itemName);
				if (obtainedNames.contains(normalizedName))
				{
					continue;
				}

				int itemId = itemIdLookup != null ? itemIdLookup.apply(itemName) : (itemName.hashCode() & 0x7FFFFFFF);
				if (itemId > 0 && obtainedIds.contains(itemId))
				{
					continue;
				}

				obtainedNames.add(normalizedName);
				if (itemId > 0)
				{
					obtainedIds.add(itemId);
				}

				double expectedRate = entry.getValue();
				items.add(new SalvageItem(itemId, itemName, 0, 0, expectedRate));
			}
		}

		int totalLoots = data != null ? data.getTotalLoots() : 0;
		Comparator<SalvageItem> comparator;

		switch (currentSortOption)
		{
			case ALPHABETICAL:
				comparator = Comparator.comparing(SalvageItem::getItemName, String.CASE_INSENSITIVE_ORDER);
				if (currentSortDescending)
				{
					comparator = comparator.reversed();
				}
				break;

			case CURRENT_RATE:
				comparator = Comparator.comparingDouble(item -> item.getCurrentDropRate(totalLoots));
				if (currentSortDescending)
				{
					comparator = comparator.reversed();
				}
				break;

			case EXPECTED_RATE:
				comparator = Comparator.comparingDouble(item -> getExpectedRate(shipwreckType, item));
				if (currentSortDescending)
				{
					comparator = comparator.reversed();
				}
				break;

			case QUANTITY:
				comparator = Comparator.comparingInt(SalvageItem::getTotalQuantity);
				if (currentSortDescending)
				{
					comparator = comparator.reversed();
				}
				break;

			case LUCK:
				comparator = (item1, item2) -> {
					double exp1 = getExpectedRate(shipwreckType, item1);
					double exp2 = getExpectedRate(shipwreckType, item2);
					boolean unk1 = exp1 <= 0;
					boolean unk2 = exp2 <= 0;
					if (unk1 && unk2)
					{
						return item1.getItemName().compareToIgnoreCase(item2.getItemName());
					}
					if (unk1) return 1;
					if (unk2) return -1;

					int score1 = getLuckScore(item1, totalLoots, exp1);
					int score2 = getLuckScore(item2, totalLoots, exp2);
					int cmp = Integer.compare(score1, score2);
					if (cmp == 0)
					{
						return item1.getItemName().compareToIgnoreCase(item2.getItemName());
					}
					return currentSortDescending ? -cmp : cmp;
				};
				break;

			default:
				comparator = Comparator.comparing(SalvageItem::getItemName, String.CASE_INSENSITIVE_ORDER);
				if (currentSortDescending)
				{
					comparator = comparator.reversed();
				}
				break;
		}

		items.sort(comparator);
		return items;
	}

	private double getExpectedRate(ShipwreckType shipwreckType, SalvageItem item)
	{
		if (dropRateManager != null)
		{
			return dropRateManager.getExpectedDropRate(shipwreckType, item.getItemName());
		}
		return item.getExpectedDropRate();
	}

	private String formatRate(double rate, boolean asOneInX)
	{
		if (rate <= 0.0)
		{
			return "-";
		}
		if (asOneInX)
		{
			double oneOver = 1.0 / rate;
			if (Math.abs(oneOver - Math.round(oneOver)) < 0.05)
			{
				return String.format("1/%,d", Math.round(oneOver));
			}
			else
			{
				return String.format("1/%,.1f", oneOver);
			}
		}
		else
		{
			return String.format("%.2f%%", rate * 100.0);
		}
	}

	private Color getLuckColor(double currentRate, double expectedRate)
	{
		if (expectedRate <= 0)
		{
			return Color.LIGHT_GRAY;
		}

		double luckRatio = currentRate / expectedRate;

		if (luckRatio >= 1.5)
		{
			return LUCK_GOOD;
		}
		else if (luckRatio >= 1.1)
		{
			float t = (float) ((luckRatio - 1.1) / 0.4);
			return interpolateColor(LUCK_NEUTRAL, LUCK_GOOD, t);
		}
		else if (luckRatio >= 0.9)
		{
			return LUCK_NEUTRAL;
		}
		else if (luckRatio >= 0.5)
		{
			float t = (float) ((luckRatio - 0.5) / 0.4);
			return interpolateColor(LUCK_BAD, LUCK_NEUTRAL, t);
		}
		else
		{
			return LUCK_BAD;
		}
	}

	private int getLuckScore(SalvageItem item, int totalLoots, double expectedRate)
	{
		if (expectedRate <= 0)
		{
			return Integer.MIN_VALUE;
		}

		if (totalLoots == 0)
		{
			return 500;
		}

		double currentRate = item.getCurrentDropRate(totalLoots);
		double luckRatio = currentRate / expectedRate;

		if (item.getDropCount() == 0)
		{
			double rolls = totalLoots * expectedRate;
			if (rolls >= 2.0)
			{
				return 0;
			}
			if (rolls >= 1.0)
			{
				return 50;
			}
			if (rolls >= 0.5)
			{
				return 250;
			}
			return 450;
		}

		if (luckRatio >= 1.5)
		{
			return 1000;
		}
		else if (luckRatio >= 1.1)
		{
			return 600 + (int)((luckRatio - 1.1) / 0.4 * 399);
		}
		else if (luckRatio >= 0.9)
		{
			return 500;
		}
		else if (luckRatio >= 0.5)
		{
			return 100 + (int)((luckRatio - 0.5) / 0.4 * 399);
		}
		else
		{
			return 0;
		}
	}

	private Color interpolateColor(Color c1, Color c2, float t)
	{
		t = Math.max(0, Math.min(1, t));
		int r = (int) (c1.getRed() + t * (c2.getRed() - c1.getRed()));
		int g = (int) (c1.getGreen() + t * (c2.getGreen() - c1.getGreen()));
		int b = (int) (c1.getBlue() + t * (c2.getBlue() - c1.getBlue()));
		return new Color(r, g, b);
	}

	/**
	 * Show context menu for shipwreck section
	 */
	private void showContextMenu(MouseEvent e, ShipwreckType type)
	{
		JPopupMenu menu = new JPopupMenu();

		JMenuItem resetItem = new JMenuItem("Reset " + type.getDisplayName() + " Data");
		resetItem.addActionListener(ev -> {
			int confirm = JOptionPane.showConfirmDialog(
				this,
				"Are you sure you want to reset all data for " + type.getDisplayName() + "?",
				"Confirm Reset",
				JOptionPane.YES_NO_OPTION,
				JOptionPane.WARNING_MESSAGE
			);
			if (confirm == JOptionPane.YES_OPTION)
			{
				expandedState.remove(type);
				if (configManager != null)
				{
					configManager.unsetConfiguration(CONFIG_GROUP, "expanded_" + type.name());
				}
				if (onResetShipwreck != null)
				{
					onResetShipwreck.accept(type);
				}
			}
		});
		menu.add(resetItem);

		menu.addSeparator();

		JMenuItem resetAllItem = new JMenuItem("Reset All Data");
		resetAllItem.addActionListener(ev -> {
			int confirm = JOptionPane.showConfirmDialog(
				this,
				"Are you sure you want to reset ALL salvage data?",
				"Confirm Reset All",
				JOptionPane.YES_NO_OPTION,
				JOptionPane.WARNING_MESSAGE
			);
			if (confirm == JOptionPane.YES_OPTION)
			{
				expandedState.clear();
				if (configManager != null)
				{
					for (ShipwreckType st : ShipwreckType.values())
					{
						configManager.unsetConfiguration(CONFIG_GROUP, "expanded_" + st.name());
					}
				}
				if (onResetAll != null)
				{
					onResetAll.run();
				}
			}
		});
		menu.add(resetAllItem);

		menu.show(e.getComponent(), e.getX(), e.getY());
	}
}
