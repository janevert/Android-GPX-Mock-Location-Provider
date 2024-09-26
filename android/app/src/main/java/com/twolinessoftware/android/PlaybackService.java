/*
 * Copyright (c) 2011 2linessoftware.com
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.twolinessoftware.android;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Criteria;
import android.location.LocationManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.twolinessoftware.android.framework.service.comms.gpx.GpxSaxParser;
import com.twolinessoftware.android.framework.service.comms.gpx.GpxSaxParserListener;
import com.twolinessoftware.android.framework.service.comms.gpx.GpxTrackPoint;
import com.twolinessoftware.android.framework.util.Logger;
import com.vividsolutions.jts.geom.Coordinate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class PlaybackService extends Service implements GpxSaxParserListener {

    private static final String NOTIFICATION_CHANNEL_ID_DEFAULT = "default";
    private static final String LOG = PlaybackService.class.getSimpleName();
    private static final String PROVIDER_NAME = LocationManager.GPS_PROVIDER;

    private static final int NOTIFICATION = 1;

    public static final int RUNNING = 0;
    public static final int STOPPED = 1;

    public static final boolean DEBUG = false;

    private GpxTrackPoint lastPoint;

    private final IPlaybackService.Stub mBinder = new IPlaybackService.Stub() {

        @Override
        public void loadGpx(Uri uri) throws RemoteException {
            queue = new SendLocationWorkerQueue();
            PlaybackService.this.loadGpx(uri);
        }

        @Override
        public void startService() throws RemoteException {
            broadcastStateChange(RUNNING);
            queue.start();
        }

        @Override
        public void stopService() throws RemoteException {
            // stop own operations
            cancelExistingTaskIfNecessary();

            // stop queue operations
            queue.reset();

            // let the rest of the world know
            broadcastStateChange(STOPPED);
            onGpsPlaybackStopped();

            stopSelf();
        }

        @Override
        public int getState() throws RemoteException {
            return state;
        }

    };

    private NotificationManager mNM;
    private LocationManager mLocationManager;
    private long startTimeOffset;
    private long firstGpsTime;
    private int state;
    private SendLocationWorkerQueue queue;
    private ReadFileTask task;

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        mNM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        broadcastStateChange(STOPPED);

        setupTestProvider();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        Logger.d(LOG, "onStartCommand");

        String timeFromIntent = null;
        try {
            timeFromIntent = intent.getStringExtra("delayTimeOnReplay");
        } catch (java.lang.NullPointerException npe) {
            // suppress npe if delay time not available.
        }

        if (timeFromIntent != null && !"".equalsIgnoreCase(timeFromIntent)) {
            long delayTimeOnReplay = Long.valueOf(timeFromIntent);
            Logger.i(LOG, "starting with delay " + delayTimeOnReplay);
            queue.setDelayTime(delayTimeOnReplay);
        }

        // We want this service to continue running until it is explicitly
        // stopped, so return sticky.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Logger.d(LOG, "onDestroy stopping Playback Service");
        queue.reset();
        onGpsPlaybackStopped();
    }

    private void cancelExistingTaskIfNecessary() {
        if (task != null) {
            try {
                task.cancel(true);
            } catch (Exception e) {
                Logger.e(LOG, "Unable to cancel playback task. May already be stopped");
            }
        }
    }

    private void loadGpx(Uri uri) {
        if (uri != null) {
            broadcastStatus(GpsPlaybackBroadcastReceiver.Status.fileLoadStarted);
            cancelExistingTaskIfNecessary();

            task = new ReadFileTask(uri);
            task.execute(null, null);

            // Display a notification about us starting.  We put an icon in the status bar.
            showNotification();
        }
    }

    private void queueGpxPositions(String xml) {
        GpxSaxParser parser = new GpxSaxParser(this);
        parser.parse(xml);
    }

    private void onGpsPlaybackStopped() {

        broadcastStateChange(STOPPED);

        // Cancel the persistent notification.
        mNM.cancel(NOTIFICATION);

        disableGpsProvider();

    }

    private void disableGpsProvider() {

        if (mLocationManager.getProvider(PROVIDER_NAME) != null) {
            Logger.i(LOG, "Removing test provider");

            mLocationManager.setTestProviderEnabled(PROVIDER_NAME, false);
            mLocationManager.clearTestProviderEnabled(PROVIDER_NAME);
            mLocationManager.clearTestProviderLocation(PROVIDER_NAME);

            mLocationManager.removeTestProvider(PROVIDER_NAME);

        }
    }

    private void setupTestProvider() {
        Logger.i(LOG, "Adding test provider");
        mLocationManager.addTestProvider(PROVIDER_NAME,
                false, //requiresNetwork,
                false, // requiresSatellite,
                false, // requiresCell,
                false, // hasMonetaryCost,
                false, // supportsAltitude,
                false, // supportsSpeed, s
                false, // upportsBearing,
                Criteria.POWER_LOW, // powerRequirement
                Criteria.ACCURACY_FINE); // accuracy

        mLocationManager.setTestProviderEnabled(PROVIDER_NAME, true);
    }


    /**
     * Show a notification while this service is running.
     */
    private void showNotification() {
        // In this sample, we'll use the same text for the ticker and the expanded notification
        CharSequence text = "GPX Playback Running";

        // The PendingIntent to launch our activity if the user selects this notification
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), 0);

        final Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID_DEFAULT)
                .setSmallIcon(R.drawable.ic_playback_running)
                .setContentText(text)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(contentIntent)
                .setContentTitle("GPX Playback Manager")
                .build();

        // Send the notification.
        mNM.notify(NOTIFICATION, notification);
    }

    private String loadUri(Uri uri) {

        InputStream input = null;
        try {
            input = getContentResolver().openInputStream(uri);
            BufferedReader buf = new BufferedReader(new InputStreamReader(input));

            String readString;
            StringBuffer xml = new StringBuffer();
            while ((readString = buf.readLine()) != null) {
                xml.append(readString);
            }

            Logger.d(LOG, "Finished reading in file");

            return xml.toString();

        } catch (Exception e) {
            broadcastError("Error in the GPX file, unable to read it");
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException e) {
                    // not expected
                }
            }
        }

        return null;
    }


    @Override
    public void onGpxError(String message) {
        broadcastError(message);
    }


    @Override
    public void onGpxPoint(GpxTrackPoint item) {

        long delay = System.currentTimeMillis() + 2000; // ms until the point should be displayed

        long gpsPointTime = 0;

        // Calculate the delay
        if (item.getTime() != null) {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

            try {
                Date gpsDate = format.parse(item.getTime());
                gpsPointTime = gpsDate.getTime();
            } catch (ParseException e) {
                Logger.e(LOG, "Unable to parse time:" + item.getTime());
            }

            if (firstGpsTime == 0)
                firstGpsTime = gpsPointTime;


            if (startTimeOffset == 0)
                startTimeOffset = System.currentTimeMillis();


            delay = (gpsPointTime - firstGpsTime) + startTimeOffset;
        }

        if (lastPoint != null) {
            item.setHeading(calculateHeadingFromPreviousPoint(lastPoint, item));
            item.setSpeed(calculateSpeedFromPreviousPoint(lastPoint, item));
        } else {
            item.setHeading(0.0);
            item.setSpeed(15.0);
        }

        lastPoint = item;

        if (delay > 0) {
            if (DEBUG) Logger.d(LOG, "Sending Point in:" + (delay - System.currentTimeMillis()) + "ms");

            SendLocationWorker worker = new SendLocationWorker(mLocationManager, item, PROVIDER_NAME, delay);
            queue.addToQueue(worker);
        } else {
            Logger.e(LOG, "Invalid Time at Point:" + gpsPointTime + " delay from current time:" + delay);
        }
    }

    private double calculateHeadingFromPreviousPoint(GpxTrackPoint currentPoint, GpxTrackPoint lastPoint) {

        double angleBetweenPoints = Math.atan2((lastPoint.getLon() - currentPoint.getLon()), (lastPoint.getLat() - currentPoint.getLat()));
        return Math.toDegrees(angleBetweenPoints);
    }

    private double calculateSpeedFromPreviousPoint(GpxTrackPoint currentPoint, GpxTrackPoint lastPoint) {

        Coordinate startCoordinate = new Coordinate(lastPoint.getLon(), lastPoint.getLat());
        Coordinate endCoordinate = new Coordinate(currentPoint.getLon(), currentPoint.getLat());
        double distance = startCoordinate.distance(endCoordinate) * 100000;
        return distance;

    }

    @Override
    public void onGpxStart() {
        // Start Parsing
    }

    @Override
    public void onGpxEnd() {
        // End Parsing
    }

    private void broadcastStatus(GpsPlaybackBroadcastReceiver.Status status) {
        Intent i = new Intent(GpsPlaybackBroadcastReceiver.INTENT_BROADCAST);
        i.putExtra(GpsPlaybackBroadcastReceiver.INTENT_STATUS, status.toString());
        sendBroadcast(i);
    }

    private void broadcastError(String message) {
        Intent i = new Intent(GpsPlaybackBroadcastReceiver.INTENT_BROADCAST);
        i.putExtra(GpsPlaybackBroadcastReceiver.INTENT_STATUS, GpsPlaybackBroadcastReceiver.Status.fileError.toString());
        i.putExtra(GpsPlaybackBroadcastReceiver.INTENT_STATE, state);
        sendBroadcast(i);
    }

    private void broadcastStateChange(int newState) {
        state = newState;
        Intent i = new Intent(GpsPlaybackBroadcastReceiver.INTENT_BROADCAST);
        i.putExtra(GpsPlaybackBroadcastReceiver.INTENT_STATUS, GpsPlaybackBroadcastReceiver.Status.statusChange.toString());
        i.putExtra(GpsPlaybackBroadcastReceiver.INTENT_STATE, state);
        sendBroadcast(i);
    }

    private class ReadFileTask extends AsyncTask<Void, Integer, Void> {

        private Uri uri;

        public ReadFileTask(Uri uri) {
            super();
            this.uri = uri;
        }

        @Override
        protected void onPostExecute(Void result) {
            broadcastStatus(GpsPlaybackBroadcastReceiver.Status.fileLoadfinished);
        }

        @Override
        protected Void doInBackground(Void... arg0) {

            // Reset the existing values
            firstGpsTime = 0;
            startTimeOffset = 0;

            String xml = loadUri(uri);
            publishProgress(1);
            queueGpxPositions(xml);

            return null;
        }
    }


}
