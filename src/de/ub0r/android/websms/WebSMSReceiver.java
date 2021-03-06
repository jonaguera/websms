/*
 * Copyright (C) 2010 Felix Bechstein
 * 
 * This file is part of WebSMS.
 * 
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later
 * version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * this program; If not, see <http://www.gnu.org/licenses/>.
 */
package de.ub0r.android.websms;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.util.Log;
import de.ub0r.android.websms.connector.common.Connector;
import de.ub0r.android.websms.connector.common.ConnectorCommand;
import de.ub0r.android.websms.connector.common.ConnectorSpec;
import de.ub0r.android.websms.connector.common.Utils;

/**
 * Fetch all incomming Broadcasts and forward them to WebSMS.
 * 
 * @author flx
 */
public final class WebSMSReceiver extends BroadcastReceiver {
	/** Tag for debug output. */
	private static final String TAG = "WebSMS.bcr";

	/** Intent's scheme to send sms. */
	private static final String INTENT_SCHEME_SMSTO = "smsto";

	/** SMS DB: address. */
	static final String ADDRESS = "address";
	/** SMS DB: person. */
	// private static final String PERSON = "person";
	/** SMS DB: date. */
	private static final String DATE = "date";
	/** SMS DB: read. */
	static final String READ = "read";
	/** SMS DB: status. */
	// private static final String STATUS = "status";
	/** SMS DB: type. */
	static final String TYPE = "type";
	/** SMS DB: body. */
	static final String BODY = "body";
	/** SMS DB: type - sent. */
	static final int MESSAGE_TYPE_SENT = 2;

	/** Next notification ID. */
	private static int nextNotificationID = 1;

	/** LED color for notification. */
	private static final int NOTIFICATION_LED_COLOR = 0xffff0000;
	/** LED blink on (ms) for notification. */
	private static final int NOTIFICATION_LED_ON = 500;
	/** LED blink off (ms) for notification. */
	private static final int NOTIFICATION_LED_OFF = 2000;

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void onReceive(final Context context, final Intent intent) {
		final String action = intent.getAction();
		Log.d(TAG, "action: " + action);
		if (action == null) {
			return;
		}
		if (Connector.ACTION_INFO.equals(action)) {
			this.handleInfoAction(context, intent);
		} else if (Connector.ACTION_CAPTCHA_REQUEST.equals(action)) {
			final Intent i = new Intent(context, CaptchaActivity.class);
			i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			i.putExtras(intent.getExtras());
			context.startActivity(i);
		}
	}

	/**
	 * Fetch INFO broadcast.
	 * 
	 * @param context
	 *            context
	 * @param intent
	 *            intent
	 */
	private void handleInfoAction(final Context context, final Intent intent) {
		final ConnectorSpec specs = new ConnectorSpec(intent);
		final ConnectorCommand command = new ConnectorCommand(intent);

		if (specs == null) {
			// security check. some other apps may send faulty broadcasts
			return;
		}

		try {
			WebSMS.addConnector(specs);
		} catch (Exception e) {
			Log.e(TAG, "error while receiving broadcast", e);
		}
		// save send messages
		if (command != null && // .
				command.getType() == ConnectorCommand.TYPE_SEND) {
			this.handleSendCommand(specs, context, intent, command);
		}
	}

	/**
	 * Save sent message or display error notification if failed sending.
	 * 
	 * @param specs
	 *            {@link ConnectorSpec}
	 * @param context
	 *            context
	 * @param intent
	 *            intent
	 * @param command
	 *            {@link ConnectorCommand}
	 */
	private void handleSendCommand(final ConnectorSpec specs,
			final Context context, final Intent intent,
			final ConnectorCommand command) {

		if (!specs.hasStatus(ConnectorSpec.STATUS_ERROR)) {
			this.saveMessage(context, command);
			return;
		}
		// Display notification if sending failed
		final String[] r = command.getRecipients();
		final int l = r.length;
		StringBuilder buf = new StringBuilder(r[0]);
		for (int i = 1; i < l; i++) {
			buf.append(", ");
			buf.append(r[i]);
		}
		final String to = buf.toString();
		buf = null;

		Notification n = new Notification(R.drawable.stat_notify_sms_failed,
				context.getString(R.string.notify_failed_), System
						.currentTimeMillis());
		final Intent i = new Intent(Intent.ACTION_SENDTO, Uri
				.parse(INTENT_SCHEME_SMSTO + ":" + Uri.encode(to)), context,
				WebSMS.class);
		// add pending intent
		i.putExtra(Intent.EXTRA_TEXT, command.getText());
		i.putExtra(WebSMS.EXTRA_ERRORMESSAGE, specs.getErrorMessage());
		i.setFlags(i.getFlags() | Intent.FLAG_ACTIVITY_NEW_TASK);
		final PendingIntent cIntent = PendingIntent.getActivity(context, 0, i,
				PendingIntent.FLAG_CANCEL_CURRENT);
		n.setLatestEventInfo(context, context.getString(R.string.notify_failed)
				+ " " + specs.getErrorMessage(), to + ": " + command.getText(),
				cIntent);
		n.flags |= Notification.FLAG_AUTO_CANCEL;

		n.flags |= Notification.FLAG_SHOW_LIGHTS;
		n.ledARGB = NOTIFICATION_LED_COLOR;
		n.ledOnMS = NOTIFICATION_LED_ON;
		n.ledOffMS = NOTIFICATION_LED_OFF;

		final SharedPreferences p = PreferenceManager
				.getDefaultSharedPreferences(context);
		final boolean vibrateOnFail = p.getBoolean(WebSMS.PREFS_FAIL_VIBRATE,
				false);
		final String s = p.getString(WebSMS.PREFS_FAIL_SOUND, null);
		Uri soundOnFail;
		if (s == null || s.length() <= 0) {
			soundOnFail = null;
		} else {
			soundOnFail = Uri.parse(s);
		}

		if (vibrateOnFail) {
			n.defaults |= Notification.DEFAULT_VIBRATE;
		}
		n.sound = soundOnFail;

		NotificationManager mNotificationMgr = // .
		(NotificationManager) context
				.getSystemService(Context.NOTIFICATION_SERVICE);
		mNotificationMgr.notify(getNotificationID(), n);
	}

	/**
	 * Get a fresh and unique ID for a new notification.
	 * 
	 * @return return the ID
	 */
	private static synchronized int getNotificationID() {
		++nextNotificationID;
		return nextNotificationID;
	}

	/**
	 * Save Message to internal database.
	 * 
	 * @param context
	 *            {@link Context}
	 * @param command
	 *            {@link ConnectorCommand}
	 */
	private void saveMessage(final Context context,
			final ConnectorCommand command) {
		if (command.getType() != ConnectorCommand.TYPE_SEND) {
			return;
		}
		final String[] recipients = command.getRecipients();
		for (int i = 0; i < recipients.length; i++) {
			if (recipients[i] == null || recipients[i].trim().length() == 0) {
				continue; // skip empty recipients
			}
			// save sms to content://sms/sent
			Log.d(TAG, "save message:");
			Log.d(TAG, "TO: " + Utils.getRecipientsNumber(recipients[i]));
			Log.d(TAG, "TEXT: " + command.getText());
			ContentValues values = new ContentValues();
			values.put(ADDRESS, Utils.getRecipientsNumber(recipients[i]));
			values.put(READ, 1);
			values.put(TYPE, MESSAGE_TYPE_SENT);
			values.put(BODY, command.getText());
			if (command.getSendLater() > 0) {
				values.put(DATE, command.getSendLater());
				Log.d(TAG, "DATE: " + command.getSendLater());
			}
			context.getContentResolver().insert(
					Uri.parse("content://sms/sent"), values);
		}
	}
}
