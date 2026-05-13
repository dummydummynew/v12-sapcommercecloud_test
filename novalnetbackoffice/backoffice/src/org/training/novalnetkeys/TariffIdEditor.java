package org.training.novalnetkeys;

import de.hybris.platform.store.BaseStoreModel;

import java.util.Map;

import org.apache.log4j.Logger;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Sessions;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventQueue;
import org.zkoss.zk.ui.event.EventQueues;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zul.Combobox;
import org.zkoss.zul.Comboitem;

import com.hybris.cockpitng.editors.CockpitEditorRenderer;
import com.hybris.cockpitng.editors.EditorContext;
import com.hybris.cockpitng.editors.EditorListener;

public class TariffIdEditor implements CockpitEditorRenderer<Integer> {
	private static final Logger LOG = Logger.getLogger(TariffIdEditor.class);

	protected static final String NOVALNET_TARIFF_EVENT = "onNovalnetTariffRefresh";

	@Override
	public void render(Component parent, EditorContext<Integer> context, EditorListener<Integer> listener) {
		LOG.info("TariffIdEditor initialized");
		BaseStoreModel baseStore = null;
		Object paramObj = context.getParameter("parentObject");

		if (paramObj instanceof BaseStoreModel) {
			baseStore = (BaseStoreModel) paramObj;
		}

		if (baseStore == null) {
			LOG.warn("BaseStore is NULL");
			return;
		}

		String storeId = baseStore.getUid();
		LOG.info("storeId " + storeId);

		Combobox combobox = new Combobox();
		combobox.setWidth("75%");
		combobox.setParent(parent);

		Integer dbValue = context.getInitialValue();
		loadTariffs(combobox, dbValue, storeId);
		EventQueue<Event> queue = EventQueues.lookup(NOVALNET_TARIFF_EVENT, EventQueues.DESKTOP, true);

		if (queue != null) {
			queue.subscribe(event -> {
				LOG.info("Received tariff refresh event");
				combobox.getItems().clear();
				loadTariffs(combobox, dbValue, storeId);
			});
		}

		combobox.addEventListener(Events.ON_SELECT, event -> {
			Comboitem selected = combobox.getSelectedItem();
			if (selected != null) {
				Integer tariffId = (Integer) selected.getValue();
				Sessions.getCurrent().setAttribute("SELECTED_TARIFF_VALUE_" + storeId, tariffId);
				LOG.info("Selected tariffId: " + tariffId);
				listener.onValueChanged(tariffId);
			}
		});
	}

	private void loadTariffs(Combobox combobox, Integer dbValue, String storeId) {
		LOG.info("loadTariffs called");
		Map<String, String> tariffs = (Map<String, String>) Sessions.getCurrent()
				.getAttribute("NOVALNET_TARIFF_NAMES_" + storeId);

		combobox.getItems().clear();
		combobox.setSelectedItem(null);
		combobox.setValue("");

		Integer sessionSelected = (Integer) Sessions.getCurrent().getAttribute("SELECTED_TARIFF_VALUE_" + storeId);

		if (tariffs == null || tariffs.isEmpty()) {
			LOG.warn("Tariff list not available yet");
			return;
		}

		LOG.info("Tariff list loaded: " + tariffs);

		Comboitem selectedItem = null;

		for (Map.Entry<String, String> entry : tariffs.entrySet()) {
			String tariffId = entry.getKey();
			String tariffName = entry.getValue();
			Comboitem item = combobox.appendItem(tariffName);
			item.setValue(Integer.valueOf(tariffId));

			if (sessionSelected != null && sessionSelected.equals(Integer.valueOf(tariffId))) {
				selectedItem = item;
			} else if (sessionSelected == null && dbValue != null && dbValue.equals(Integer.valueOf(tariffId))) {
				selectedItem = item;
			}
		}

		if (selectedItem != null) {
			combobox.setSelectedItem(selectedItem);
			LOG.info("Selected tariff from session/db: " + selectedItem.getValue());
		} else if (!tariffs.isEmpty()) {
			combobox.setSelectedIndex(0);

			Sessions.getCurrent().setAttribute("SELECTED_TARIFF_VALUE_" + storeId,
					combobox.getSelectedItem().getValue());

			LOG.info("Default tariff selected: " + combobox.getSelectedItem().getValue());
		}
	}
}