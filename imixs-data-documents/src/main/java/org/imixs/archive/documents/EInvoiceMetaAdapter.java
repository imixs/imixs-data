package org.imixs.archive.documents;

import java.util.logging.Logger;

import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;

/**
 * 
 * @author rsoika
 * @version 2.0
 * 
 */
@Deprecated
public class EInvoiceMetaAdapter extends org.imixs.workflow.documents.EInvoiceMetaAdapter {
	private static Logger logger = Logger.getLogger(EInvoiceAdapter.class.getName());

	/**
	 *
	 */
	@Deprecated
	@Override
	public ItemCollection execute(ItemCollection workitem, ItemCollection event)
			throws AdapterException, PluginException {

		logger.warning(
				"This EInvoiceMetaAdapter is deprecated and should be replaced with 'org.imixs.workflow.documents.EInvoiceMetaAdapter'");
		return super.execute(workitem, event);
	}

}
