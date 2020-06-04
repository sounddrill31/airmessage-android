package me.tagavari.airmessage.service;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import java.lang.ref.WeakReference;
import java.util.Timer;
import java.util.TimerTask;

import me.tagavari.airmessage.BuildConfig;
import me.tagavari.airmessage.MainApplication;
import me.tagavari.airmessage.R;
import me.tagavari.airmessage.activity.Conversations;
import me.tagavari.airmessage.connection.ConnectionManager;
import me.tagavari.airmessage.util.Constants;

public class ConnectionService extends Service {
	//Creating the constants
	public static final long keepAliveMillis = 20 * 60 * 1000; //30 * 60 * 1000; //20 minutes
	public static final long keepAliveWindowMillis = 5 * 60 * 1000; //5 minutes
	public static final long[] dropReconnectDelayMillis = {1 * 1000, 10 * 1000, 30 * 1000}; //1 second, 10 seconds, 30 seconds
	public static final long passiveReconnectFrequencyMillis = 20 * 60 * 1000; //20 minutes
	public static final long passiveReconnectWindowMillis = 5 * 60 * 1000; //5 minutes
	public static final long temporaryModeExpiry = 15 * 1000; //15 seconds
	
	public static final String selfIntentActionConnect = "connect";
	public static final String selfIntentActionDisconnect = "disconnect";
	public static final String selfIntentActionStop = "stop";
	
	public static final String selfIntentExtraConfig = "configuration_mode";
	public static final String selfIntentExtraTemporary = "temporary_mode";
	
	private static final String BCPingTimer = "me.tagavari.airmessage.connection.ConnectionService-StartPing";
	private static final String BCReconnectTimer = "me.tagavari.airmessage.connection.ConnectionService-StartReconnect";
	
	private static final int notificationID = MainApplication.notificationIDConnectionService;
	private static final int notificationAlertID = MainApplication.notificationIDConnectionServiceAlert;
	
	//Creating the access values
	private static WeakReference<ConnectionService> serviceReference = null;
	
	//Creating the state values
	private boolean isFirstLaunch = true;
	private boolean configurationMode = false;
	private boolean temporaryMode = false;
	private Timer temporaryModeTimer = null;
	private int dropReconnectIndex = 0;
	
	//Creating the intent values
	private PendingIntent pingPendingIntent;
	private PendingIntent reconnectPendingIntent;
	
	//Drop reconnect values
	private int dropReconnectIndex = 0;
	private boolean dropReconnectRunning = false;
	private Handler handler;
	
	//private final List<FileUploadRequest> fileUploadRequestQueue = new ArrayList<>();
	//private Thread fileUploadRequestThread = null;
	
	//Creating the broadcast receivers
	private final BroadcastReceiver pingBroadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			connectionManager.ping();
		}
	};
	private final Runnable connectRunnable = new Runnable() {
		@Override
		public void run() {
			dropReconnectRunning = false;
			
			//Returning if the service isn't running or we're in configuration mode
			if(getInstance() == null || configurationMode) return;
			
			//Connecting to the server
			connectionManager.connect(MainApplication.getInstance(), ConnectionManager.getNextLaunchID());
		}
	};
	private final BroadcastReceiver reconnectionBroadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			//Connecting to the server
			if(!configurationMode) connectionManager.connect(context);
			
			//Scheduling the next reconnection
			reschedulePassiveReconnection();
		}
	};
	private final BroadcastReceiver networkStateChangeBroadcastReceiver = new BroadcastReceiver() {
		private long lastDisconnectionTime = -1;
		@Override
		public void onReceive(Context context, Intent intent) {
			//Returning if automatic reconnects are disabled
			if(configurationMode) return;
			//if(!PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getResources().getString(R.string.preference_server_networkreconnect_key), false)) return;
			
			//Getting the current active network
			NetworkInfo activeNetwork = intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);
			//NetworkInfo activeNetwork = context.getSystemService(ConnectivityManager.class).getActiveNetworkInfo();
			//if(activeNetwork == null) return;
			
			if(activeNetwork.isConnected()) {
				//Reconnecting
				connectionManager.reconnect(context);
				
				//Also reset drop reconnect
				dropReconnectIndex = 0;
			} else {
				//Disconnecting
				connectionManager.disconnect();
				
				//Marking the time
				lastDisconnectionTime = SystemClock.elapsedRealtime();
			}
		}
	};
	private final BroadcastReceiver serverConnectionStateChangeBroadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			//Getting the state
			int state = intent.getIntExtra(Constants.intentParamState, -1);
			if(state == -1) return;
			
			if(state == ConnectionManager.stateConnected) {
				//Updating the notification
				postConnectedNotification(true, connectionManager.isConnectedFallback());
                
                //Also reset drop reconnect
                dropReconnectIndex = 0;
                disconnectedSoundSinceReconnect = false;
				
				//Checking if we are in temporary mode
				if(temporaryMode) {
					//Scheduling a shutdown
					if(temporaryModeTimer != null) temporaryModeTimer.cancel();
					temporaryModeTimer = new Timer();
					temporaryModeTimer.schedule(new TimerTask() {
						@Override
						public void run() {
							//Disabling temporary mode
							disableTemporaryMode();
							
							//Shutting down the connection service
							shutDownService();
						}
					}, temporaryModeExpiry);
				}
			} else if(state == ConnectionManager.stateConnecting) {
				//Updating the notification
				postConnectedNotification(false, false);
			} else if(state == ConnectionManager.stateDisconnected) {
				//Checking if we are in temporary mode
				if(temporaryMode) {
					//Disabling temporary mode
					disableTemporaryMode();
					
					//Shutting down the connection service
					shutDownService();
				} else {
					//Updating the notification
					postDisconnectedNotification(connectionManager.getLastConnectionResult() != ConnectionManager.connResultSuccess);
				}
			}
		}
	};
	
	//Creating the other values
	private static boolean disconnectedSoundSinceReconnect = false;
	
	private final ConnectionManager connectionManager = new ConnectionManager(new ConnectionManager.ServiceCallbacks() {
		@Override
		public void schedulePing() {
			ConnectionService.this.schedulePing();
		}
		
		@Override
		public void cancelSchedulePing() {
			ConnectionService.this.cancelSchedulePing();
		}
		
		@Override
		public void schedulePassiveReconnection() {
			scheduleReconnection();
		}
		
		@Override
		public void cancelSchedulePassiveReconnection() {
			cancelScheduleReconnection();
		}
	});
	
	public static ConnectionService getInstance() {
		return serviceReference == null ? null : serviceReference.get();
	}
	
	public static ConnectionManager getConnectionManager() {
		ConnectionService service = getInstance();
		if(service == null) return null;
		return service.connectionManager;
	}
	
	public static int getStaticActiveCommunicationsVersion() {
		//Getting the instance
		ConnectionManager connectionManager = getConnectionManager();
		if(connectionManager == null) return -1;
		
		//Returning the active communications version
		return connectionManager.getActiveCommunicationsVersion();
	}
	
	public static int getStaticActiveCommunicationsSubVersion() {
		//Getting the instance
		ConnectionManager connectionManager = getConnectionManager();
		if(connectionManager == null) return -1;
		
		//Returning the active communications version
		return connectionManager.getActiveCommunicationsSubVersion();
	}
	
	/**
	 * Schedules the next keepalive ping
	 */
	private void schedulePing() {
		((AlarmManager) getSystemService(Context.ALARM_SERVICE)).setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP,
				SystemClock.elapsedRealtime() + keepAliveMillis - keepAliveWindowMillis,
				keepAliveWindowMillis * 2,
				pingPendingIntent);
	}
	
	/**
	 * Cancels the timer that sends keepalive pings
	 */
	void cancelSchedulePing() {
		((AlarmManager) getSystemService(Context.ALARM_SERVICE)).cancel(pingPendingIntent);
	}
	
	/**
	 * Schedules a pending intent to passively attempt to reconnect to the server
	 */
	void scheduleReconnection() {
		//Scheduling immediate handler reconnection
		if(dropReconnectIndex < dropReconnectDelayMillis.length && !dropReconnectRunning) {
			dropReconnectRunning = true;
			handler.postDelayed(connectRunnable, dropReconnectDelayMillis[dropReconnectIndex++]);
		}
		
		//Scheduling passive reconnection
		reschedulePassiveReconnection();
	}
	
	private void reschedulePassiveReconnection() {
		((AlarmManager) getSystemService(Context.ALARM_SERVICE)).setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP,
				SystemClock.elapsedRealtime() + passiveReconnectFrequencyMillis - passiveReconnectWindowMillis,
				passiveReconnectWindowMillis * 2,
				reconnectPendingIntent);
	}
	
	/**
	 * Cancels the pending intent to passively reconnect to the server
	 */
	void cancelScheduleReconnection() {
		//Cancelling handler reconnection
		handler.removeCallbacks(connectRunnable);
		dropReconnectRunning = false;
		
		//Cancelling passive reconnection
		((AlarmManager) getSystemService(Context.ALARM_SERVICE)).cancel(reconnectPendingIntent);
	}
	
	public static boolean staticCheckSupportsFeature(String feature) {
		ConnectionManager connectionManager = getConnectionManager();
		if(connectionManager == null) return false;
		return connectionManager.checkSupportsFeature(feature);
	}
	
	@Override
	public void onCreate() {
		//Setting the instance
		serviceReference = new WeakReference<>(this);
		
		//Initializing the connection manager
		connectionManager.init(this);
		
		//Registering the broadcast receivers
		registerReceiver(networkStateChangeBroadcastReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
		registerReceiver(pingBroadcastReceiver, new IntentFilter(BCPingTimer));
		registerReceiver(reconnectionBroadcastReceiver, new IntentFilter(BCReconnectTimer));
		LocalBroadcastManager.getInstance(this).registerReceiver(serverConnectionStateChangeBroadcastReceiver, new IntentFilter(ConnectionManager.localBCStateUpdate));
		
		//Setting the reference values
		pingPendingIntent = PendingIntent.getBroadcast(this, 0, new Intent(BCPingTimer), PendingIntent.FLAG_UPDATE_CURRENT);
		reconnectPendingIntent = PendingIntent.getBroadcast(this, 1, new Intent(BCReconnectTimer), PendingIntent.FLAG_UPDATE_CURRENT);
		
		handler = new Handler();
		
		//Starting the service as a foreground service (if enabled in the preferences)
		if(foregroundServiceRequested()) startForeground(notificationID, getBackgroundNotification(false, false));
	}
	
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if(intent != null) {
			//Updating configuration mode
			if(intent.hasExtra(selfIntentExtraConfig)) setConfigurationMode(intent.getBooleanExtra(selfIntentExtraConfig, false));
			
			//Updating temporary mode
			if(intent.hasExtra(selfIntentExtraTemporary)) {
				//Don't enable temporary mode if the service is already running
				//for example, if the user is running the app in the foreground and they receive a message
				if(isFirstLaunch) {
					enableTemporaryMode();
				}
			}
			else disableTemporaryMode();
		}
		
		//Getting the intent action
		String intentAction = intent != null ? intent.getAction() : null;
		
		//Checking if a stop has been requested
		if(selfIntentActionStop.equals(intentAction)) {
			//Shutting down the connection service
			shutDownService();
			
			//Returning not sticky
			return START_NOT_STICKY;
		}
		//Checking if a disconnection has been requested
		else if(selfIntentActionDisconnect.equals(intentAction)) {
			//Removing the reconnection flag
			connectionManager.setFlagShutdownRequested();
			
			//Disconnecting
			connectionManager.disconnect();
			
			//Updating the notification
			postDisconnectedNotification(true);
		}
		//Reconnecting the client if requested
		else if(connectionManager.getCurrentState() == ConnectionManager.stateDisconnected || selfIntentActionConnect.equals(intentAction)) {
			byte launchID;
			if(intent != null && intent.hasExtra(Constants.intentParamLaunchID)) {
				launchID = intent.getByteExtra(Constants.intentParamLaunchID, (byte) 0);
			} else {
				launchID = ConnectionManager.getNextLaunchID();
			}
			
			connectionManager.connect(this, launchID);
		}
		
		//Calling the listeners
		//for(ServiceStartCallback callback : startCallbacks) callback.onServiceStarted(this);
		//startCallbacks.clear();
		
		//Clearing the first launch flag
		isFirstLaunch = false;
		
		//Returning sticky service
		return START_STICKY;
	}
	
	@Override
	public void onDestroy() {
		//Removing the instance
		serviceReference = null;
		
		//Disconnecting
		connectionManager.disconnect();
		
		//Disabling the reconnect timer
		cancelScheduleReconnection();
		
		//Unregistering the broadcast receivers
		unregisterReceiver(networkStateChangeBroadcastReceiver);
		unregisterReceiver(pingBroadcastReceiver);
		unregisterReceiver(reconnectionBroadcastReceiver);
		LocalBroadcastManager.getInstance(this).unregisterReceiver(serverConnectionStateChangeBroadcastReceiver);
		
		//Removing the notification
		clearNotification();
		
		//Clearing the instance
		serviceReference = null;
	}
	
	@Override
	public void onTaskRemoved(Intent rootIntent) {
		//Stopping the service if it isn't required to be running
		if(!connectionManager.requiresPersistence()) {
			shutDownService();
		}
	}
	
	public void shutDownService() {
		//Setting the service as shutting down
		connectionManager.setFlagShutdownRequested();
		
		//Disconnecting
		connectionManager.disconnect();
		
		//Stopping the service
		stopSelf();
	}
	
	public void setConfigurationMode(boolean configurationMode) {
		//Updating the value
		this.configurationMode = configurationMode;
		
		//Rebuilding notifications
		updateNotification();
	}
	
	public void enableTemporaryMode() {
		//Updating the value
		temporaryMode = true;
	}
	
	public void disableTemporaryMode() {
		//Updating the value
		temporaryMode = false;
		
		//Resetting the timer
		if(temporaryModeTimer != null) {
			temporaryModeTimer.cancel();
			temporaryModeTimer = null;
		}
	}
	
	/* void setForegroundState(boolean foregroundState) {
		int currentState = getCurrentState();
		
		if(foregroundState) {
			Notification notification;
			if(currentState == stateConnected) notification = getBackgroundNotification(true);
			else if(currentState == stateConnecting) notification = getBackgroundNotification(false);
			else notification = getOfflineNotification(true);
			
			startForeground(-1, notification);
		} else {
			if(disconnectedNotificationRequested() && currentState == stateDisconnected) {
				stopForeground(false);
				postDisconnectedNotification(true);
			} else {
				stopForeground(true);
			}
		}
	} */
	
	private boolean foregroundServiceRequested() {
		return true;
		//return PreferenceManager.getDefaultSharedPreferences(this).getBoolean(getResources().getString(R.string.preference_server_foregroundservice_key), false);
		//return connectionManager.requiresPersistence();
	}
	
	private boolean disconnectedNotificationRequested() {
		return true;
		//return PreferenceManager.getDefaultSharedPreferences(this).getBoolean(getResources().getString(R.string.preference_server_disconnectionnotification_key), true);
		//return connectionManager.requiresPersistence();
	}
	
	private void postConnectedNotification(boolean isConnected, boolean isFallback) {
		if(isConnected && !foregroundServiceRequested()) return;
		
		NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		if(!isStatusNotificationEnabled(notificationManager)) {
			//Clearing the separate disconnection notification
			notificationManager.cancel(notificationAlertID);
		} else {
			//Updating the status ID
			notificationManager.notify(notificationID, getBackgroundNotification(isConnected, isFallback));
		}
	}
	
	private void postDisconnectedNotification(boolean silent) {
		if((!foregroundServiceRequested() && !disconnectedNotificationRequested())) return;
		
		NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		if(!isStatusNotificationEnabled(notificationManager)) {
			//Posting the notification under a separate ID
			notificationManager.notify(notificationAlertID, getOfflineNotification(silent, MainApplication.notificationChannelStatusImportant));
		} else {
			//Updating the status notification
			String channelID = isStatusImportantNotificationEnabled(notificationManager) && !silent ? MainApplication.notificationChannelStatusImportant : MainApplication.notificationChannelStatus; //Reverting to the regular status channel if the important channel is disabled
			notificationManager.notify(notificationID, getOfflineNotification(silent, channelID));
		}
		
		if(!silent) disconnectedSoundSinceReconnect = true;
	}
	
	//Silently rebuild and update the current notification
	private void updateNotification() {
		int state = connectionManager.getCurrentState();
		
		if(state == ConnectionManager.stateConnected) postConnectedNotification(true, connectionManager.isConnectedFallback());
		else if(state == ConnectionManager.stateConnecting) postConnectedNotification(false, false);
		else if(state == ConnectionManager.stateDisconnected) postDisconnectedNotification(true);
	}
	
	private void clearNotification() {
		NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		notificationManager.cancel(notificationID);
		notificationManager.cancel(notificationAlertID);
	}
	
	private boolean isStatusNotificationEnabled(NotificationManager notificationManager) {
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			return notificationManager.getNotificationChannel(MainApplication.notificationChannelStatus).getImportance() != NotificationManager.IMPORTANCE_NONE;
		} else return true; //Nothing we can do anyways, if the user has blocked all app notifications
	}
	
	private boolean isStatusImportantNotificationEnabled(NotificationManager notificationManager) {
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			return notificationManager.getNotificationChannel(MainApplication.notificationChannelStatusImportant).getImportance() != NotificationManager.IMPORTANCE_NONE;
		} else return true; //Nothing we can do anyways, if the user has blocked all app notifications
	}
	
	/* private void finishService() {
		//Returning to a background service
		stopForeground(true);
		
		//Scheduling the task
		new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
			@Override
			public void run() {
				//Showing the offline notification
				if(PreferenceManager.getDefaultSharedPreferences(ConnectionService.this).getBoolean(getResources().getString(R.string.preference_server_disconnectionnotification_key), true)) {
					NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
					notificationManager.notify(NotificationUtils.notificationTagStatus, -1, getOfflineNotification());
				}
				
				//Stopping the service
				stopSelf();
			}
		}, 1);
	} */
	
	private Notification getBackgroundNotification(boolean isConnected, boolean isFallback) {
		//Building the notification
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, MainApplication.notificationChannelStatus)
				.setSmallIcon(R.drawable.push)
				.setContentTitle(getResources().getString(isConnected ? (isFallback ? R.string.message_connection_connectedfallback : R.string.message_connection_connected) : R.string.progress_connectingtoserver));
		
		if(configurationMode) {
			//Setting the builder text as "manual configuration status"
			builder.setContentText(getResources().getString(R.string.part_manualconfigurationstatus));
		} else {
			//Setting the helper text as "tap to open the app"
			builder.setContentText(getResources().getString(R.string.imperative_tapopenapp))
					.setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, Conversations.class), PendingIntent.FLAG_UPDATE_CURRENT));
			
			//Disconnect (only available in debug)
			if(BuildConfig.DEBUG) builder.addAction(R.drawable.wifi_off, getResources().getString(R.string.action_disconnect), PendingIntent.getService(this, 0, new Intent(this, ConnectionService.class).setAction(selfIntentActionDisconnect), PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_CANCEL_CURRENT));
		}
		
		Notification notification = builder
				.addAction(R.drawable.close_circle, getResources().getString(R.string.action_quit), PendingIntent.getService(this, 0, new Intent(this, ConnectionService.class).setAction(selfIntentActionStop), PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_CANCEL_CURRENT))
				.setShowWhen(false)
				.setPriority(Notification.PRIORITY_MIN)
				.setOnlyAlertOnce(true)
				.build();
		
		//Setting the notification as ongoing
		notification.flags = Notification.FLAG_ONGOING_EVENT;
		
		//Returning the notification
		return notification;
	}
	
	private Notification getOfflineNotification(boolean silent, String channelID) {
		//Building the notification
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelID)
				.setSmallIcon(R.drawable.warning)
				.setContentTitle(getResources().getString(R.string.message_connection_disconnected))
				.setContentText(getResources().getString(R.string.imperative_tapopenapp))
				.setColor(getResources().getColor(R.color.colorServerDisconnected, null));
		
		//Configuring configuration mode-specific changes
		if(configurationMode) {
			//Setting the builder text as "manual configuration status"
			builder.setContentText(getResources().getString(R.string.part_manualconfigurationstatus));
		} else {
			//Setting the helper text as "tap to open the app"
			builder.setContentText(getResources().getString(R.string.imperative_tapopenapp))
					.setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, Conversations.class), PendingIntent.FLAG_UPDATE_CURRENT));
			
			//Adding the reconnect action
			builder.addAction(R.drawable.wifi, getResources().getString(R.string.action_reconnect), PendingIntent.getService(this, 0, new Intent(this, ConnectionService.class), PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_CANCEL_CURRENT));
		}
		
		//Completing and returning the notification
		return builder
				.addAction(R.drawable.close_circle, getResources().getString(R.string.action_quit), PendingIntent.getService(this, 0, new Intent(this, ConnectionService.class).setAction(selfIntentActionStop), PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_CANCEL_CURRENT))
				.setShowWhen(false)
				.setPriority(NotificationCompat.PRIORITY_HIGH)
				.setOnlyAlertOnce(silent)
				.build();
	}
	
	@Override
	public IBinder onBind(Intent intent) {
		//Returning
		return null;
		//Starting the service
		//startService(intent);
		
		//Returning the binder
		//return binder;
	}
	
	/* class ConnectionBinder extends Binder {
		ConnectionService getService() {
			return ConnectionService.this;
		}
	} */
}