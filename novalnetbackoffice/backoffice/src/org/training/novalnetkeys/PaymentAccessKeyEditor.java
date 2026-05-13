package org.training.novalnetkeys;

import de.hybris.platform.servicelayer.config.ConfigurationService;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.store.BaseStoreModel;

import java.util.HashMap;
import java.util.Map;

import org.apache.log4j.Logger;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Sessions;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventQueue;
import org.zkoss.zk.ui.event.EventQueues;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zul.Messagebox;
import org.zkoss.zul.Textbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybris.cockpitng.editors.CockpitEditorRenderer;
import com.hybris.cockpitng.editors.EditorContext;
import com.hybris.cockpitng.editors.EditorListener;

import novalnet.novalnetcheckoutaddon.facades.impl.NovalnetFacade;

public class PaymentAccessKeyEditor implements CockpitEditorRenderer<String> {
	private static final Logger LOG = Logger.getLogger(PaymentAccessKeyEditor.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();
	protected static final String NOVALNET_TARIFF_EVENT = "onNovalnetTariffRefresh";
	private NovalnetFacade novalnetFacade;
	private ConfigurationService configurationService;
	private ModelService modelService;

	public void setModelService(ModelService modelService) {
		this.modelService = modelService;
	}

	public void setNovalnetFacade(NovalnetFacade novalnetFacade) {
		this.novalnetFacade = novalnetFacade;
	}

	public void setConfigurationService(ConfigurationService configurationService) {
		this.configurationService = configurationService;
	}

	@Override
	public void render(Component parent, EditorContext<String> context, EditorListener<String> listener) {
		LOG.info("PaymentAccessKeyEditor initialized");
		BaseStoreModel baseStore = null;
		Object paramObj = context.getParameter("parentObject");

		if (paramObj instanceof BaseStoreModel) {
			baseStore = (BaseStoreModel) paramObj;
		}
		if (baseStore == null) {
			try {
				throw new IllegalArgumentException("BaseStore cannot be null");
			} catch (IllegalArgumentException e) {
				LOG.error("BaseStore cannot be null", e);
				Messagebox.show(e.getMessage(), "Error", Messagebox.OK, Messagebox.ERROR);
				return;
			}
		}
		String storeId = baseStore.getUid();
		LOG.info("storeId: " + storeId);
		Textbox textbox = new Textbox();
		textbox.setWidth("75%");
		textbox.setParent(parent);

		String dbValue = context.getInitialValue();
		if (isPopulated(dbValue)) {
			textbox.setValue(dbValue);
			Sessions.getCurrent().setAttribute("PAYMENT_KEY_" + storeId, dbValue.trim());
			try {
				processNovalnetMerchantDetails(context, storeId);
			} catch (Exception e) {
				LOG.error("Error while calling Novalnet API on init", e);
			}
		}
		textbox.addEventListener(Events.ON_CHANGE, event -> {
			String paymentKey = textbox.getValue();
			listener.onValueChanged(paymentKey);
			Sessions.getCurrent().setAttribute("PAYMENT_KEY_" + storeId, paymentKey.trim());
			try {
				processNovalnetMerchantDetails(context, storeId);
			} catch (Exception e) {
				LOG.error("Error while calling Novalnet API on change", e);
			}
		});
	}

	private void processNovalnetMerchantDetails(EditorContext<String> context, String storeId) throws Exception {
		String productKey = (String) Sessions.getCurrent().getAttribute("PRODUCT_KEY_" + storeId);
		String paymentKey = (String) Sessions.getCurrent().getAttribute("PAYMENT_KEY_" + storeId);

		if (productKey == null || productKey.trim().isEmpty() || paymentKey == null || paymentKey.trim().isEmpty()) {
			LOG.warn("Novalnet skipped due to missing keys");
			return;
		}
		LOG.info("Calling Novalnet with both keys");
		String response = callNovalnetMerchantDetails(paymentKey, productKey, context);
		LOG.info("Novalnet Response: " + response);
		Map<String, Object> jsonMap = MAPPER.readValue(response, Map.class);
		Map<String, Object> result = (Map<String, Object>) jsonMap.get("result");
		String status = (String) result.get("status");
		Map<String, String> tariffMapSession = new HashMap<>();
		if ("SUCCESS".equalsIgnoreCase(status)) {
			Object paramObj = context.getParameter("parentObject");

			Map<String, Object> merchantMap = (Map<String, Object>) jsonMap.get("merchant");
			Map<String, Object> tariffMap = (Map<String, Object>) merchantMap.get("tariff");
			String clientKey = (String) merchantMap.get("client_key");

			if (paramObj instanceof BaseStoreModel) {
				BaseStoreModel baseStore = (BaseStoreModel) paramObj;
				baseStore.setNovalnetClientKey(clientKey);
				modelService.save(baseStore);
				modelService.refresh(baseStore);
				LOG.info("ClientKey saved successfully");
			}

			for (Map.Entry<String, Object> entry : tariffMap.entrySet()) {
				String tariffId = entry.getKey();
				Map<String, Object> tariff = (Map<String, Object>) entry.getValue();
				String name = (String) tariff.get("name");

				if (name != null) {
					tariffMapSession.put(tariffId, name);
				}
			}
			Sessions.getCurrent().setAttribute("NOVALNET_TARIFF_NAMES_" + storeId, tariffMapSession);
		} else {
			Sessions.getCurrent().setAttribute("NOVALNET_TARIFF_NAMES_" + storeId, new HashMap<>());
			Map<String, Object> Failureresult = (Map<String, Object>) jsonMap.get("result");
			String statustext = (String) Failureresult.get("status_text");
			Messagebox.show(statustext, "Error", Messagebox.OK, Messagebox.ERROR);
			LOG.warn("Novalnet API failed: " + statustext);
		}

		EventQueue<Event> queue = EventQueues.lookup(NOVALNET_TARIFF_EVENT, EventQueues.DESKTOP, true);
		queue.publish(new Event(NOVALNET_TARIFF_EVENT));
	}

	private String callNovalnetMerchantDetails(String paymentAccessKey, String productActivationKey,
			EditorContext<String> context) throws Exception {
		String password = paymentAccessKey.trim();
		BaseStoreModel baseStore = null;
		Object paramObj = context.getParameter("parentObject");
		if (paramObj instanceof BaseStoreModel) {
			baseStore = (BaseStoreModel) paramObj;
		}
		if (baseStore.getDefaultLanguage() == null) {
			try {
				throw new IllegalArgumentException("Default language is not configured");
			} catch (IllegalArgumentException e) {
				LOG.error("Default language is not configured", e);
				Messagebox.show(e.getMessage(), "Error", Messagebox.OK, Messagebox.ERROR);
			}
		}
		String languageIso = baseStore.getDefaultLanguage().getIsocode().toUpperCase();
		Map<String, Object> requestMap = new HashMap<>();
		Map<String, Object> merchantMap = new HashMap<>();
		merchantMap.put("signature", productActivationKey);

		Map<String, Object> customMap = new HashMap<>();
		customMap.put("lang", languageIso);

		requestMap.put("merchant", merchantMap);
		requestMap.put("custom", customMap);

		String jsonBody = MAPPER.writeValueAsString(requestMap);
		LOG.debug("Request Body: " + jsonBody);

		return novalnetFacade.fetchMerchantDetails(getMerchantDetailsUrl(), jsonBody, baseStore);

	}

	private boolean isPopulated(String val) {
		return val != null && !val.trim().isEmpty();
	}

	private String getMerchantDetailsUrl() {
		return configurationService.getConfiguration().getString("novalnet.merchant.details.url");
	}
}