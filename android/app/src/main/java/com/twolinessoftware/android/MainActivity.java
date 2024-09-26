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

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.twolinessoftware.android.framework.util.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;

public class MainActivity extends Activity implements GpsPlaybackListener {
	private static final int REQUEST_FILE = 1;

	private static final String LOGNAME = "SimulatedGPSProvider.MainActivity";

	private ServiceConnection connection;
	private IPlaybackService service;

	private EditText mEditText;
	private EditText mEditTextDelay;
	private ProgressDialog progressDialog;
	private Button mStart;
	private Button mStop;

	private Uri fileUri;
	private Uri nextUri = null;
	private String delayTimeOnReplay = "1000";

	private GpsPlaybackBroadcastReceiver receiver;
	private int state;

	private static final String APP_DATA_CACHE_FILENAME = "gpx_app_data_cache";
	private static final String DEFAULT_PATH_TO_GPX_FILE = "/";

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		Logger.d(LOGNAME, "onCreate");
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		mEditText = (EditText) findViewById(R.id.file_path);

		TextView mLabelEditText = (TextView) findViewById(R.id.label_edit_text_delay);
		mLabelEditText.setText("Input Playback Delay (milliseconds): ");
		mLabelEditText.setTextSize(17);

		mEditTextDelay = (EditText) findViewById(R.id.editTextDelay);
		mEditTextDelay.setText(delayTimeOnReplay);
		mEditTextDelay.setOnFocusChangeListener(new View.OnFocusChangeListener() {
			public void onFocusChange(View v, boolean hasFocus) {
				Logger.i(LOGNAME, "onFocusChange(delay)");
				if (!hasFocus) {
					delayTimeOnReplay = mEditTextDelay.getText().toString();
					Intent i = new Intent(getApplicationContext(), PlaybackService.class);
					i.putExtra("delayTimeOnReplay", delayTimeOnReplay);
					startService(i);
				}
			}
		});

		mStart = (Button) findViewById(R.id.start);
		mStart.setEnabled(false);
		mStart.setOnClickListener(v -> onClickStart(v));

		mStop = (Button) findViewById(R.id.stop);
		mStop.setEnabled(false);
		mStop.setOnClickListener(v -> onClickStop(v));

		// everything ready, now go and look at the intent, if any
		Intent intent = getIntent();
		if (intent != null && intent.getDataString() != null) {
			Logger.d(LOGNAME, "intent action=" + intent.getAction() + " cat=" + intent.getCategories() + " data=" + intent.getDataString());
			if (service != null) {
				try {
					service.loadGpx(intent.getData());
				} catch (RemoteException e) {
					Log.w(LOGNAME, "Failed to start the service", e);
				}
			} else {
				Logger.i(LOGNAME, "saving uri for later");
				nextUri = intent.getData();
			}
		}
	}

	@Override
	protected void onStart() {
		bindStatusListener();
		connectToService();
		super.onStart();
	}

	@Override
	protected void onStop() {
		if (receiver != null)
			unregisterReceiver(receiver);

		unbindService(connection);
		super.onStop();
	}

	private void hideProgressDialog() {
		if (progressDialog != null)
			progressDialog.cancel();
	}

	private void showProgressDialog() {
		// Display progress dialog

		progressDialog = ProgressDialog.show(this,
				getString(R.string.please_wait),
				getString(R.string.loading_file), true);
		progressDialog.setCancelable(true);
		progressDialog.setOnCancelListener(new OnCancelListener() {
			@Override
			public void onCancel(DialogInterface dialog) {
				dialog.dismiss();
			}
		});
	}

	private void bindStatusListener() {
		receiver = new GpsPlaybackBroadcastReceiver(this);
		IntentFilter filter = new IntentFilter();
		filter.addAction(GpsPlaybackBroadcastReceiver.INTENT_BROADCAST);
		registerReceiver(receiver, filter);
	}

	private void connectToService() {
		Intent i = new Intent(getApplicationContext(), PlaybackService.class);
		connection = new PlaybackServiceConnection();
		bindService(i, connection, Context.BIND_AUTO_CREATE);
	}

	public void onClickOpenFile(View view) {
		openFile();
	}

	public void onClickStart(View view) {
		startPlaybackService();
	}

	public void onClickStop(View view) {
		stopPlaybackService();
	}

	/**
	 * Opens the file manager to select a file to open.
	 */
	public void openFile() {

		String fileName = getGpxFilePath();

		Intent intent = new Intent(FileManagerIntents.ACTION_PICK_FILE);

		// Construct URI from file name.
		File file = new File(fileName);
		intent.setData(Uri.fromFile(file));

		// Set fancy title and button (optional)
		intent.putExtra(FileManagerIntents.EXTRA_TITLE,
				getString(R.string.open_title));
		intent.putExtra(FileManagerIntents.EXTRA_BUTTON_TEXT,
				getString(R.string.open_button));

		try {
			startActivityForResult(intent, REQUEST_FILE);
		} catch (ActivityNotFoundException e) {
			// No compatible file manager was found.
			Toast.makeText(this, R.string.no_filemanager_installed,
					Toast.LENGTH_SHORT).show();
		}
	}

	/*
	 * "Start" button clicked
	 */
	public void startPlaybackService() {
		Logger.i(LOGNAME, "starting playback...");
		try {
			if (service != null) {
				service.startService();
			}
		} catch (RemoteException e) {
			Log.w(LOGNAME, "Exception while starting playback", e);
		}
	}

	public void stopPlaybackService() {
		try {
			if (service != null) {
				service.stopService();
			}
		} catch (RemoteException e) {
			Log.w(LOGNAME, "Exception while stopping playback", e);
		}
	}

	private void updateUi() {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				if (service == null) {
					mStart.setEnabled(false);
					mStop.setEnabled(false);
				} else {
					switch (state) {
						case PlaybackService.RUNNING:
							mStart.setEnabled(false);
							mStop.setEnabled(true);
							break;
						case PlaybackService.STOPPED:
							mStart.setEnabled(true);
							mStop.setEnabled(false);
							break;
					}
				}
			}
		});
	}

	class PlaybackServiceConnection implements ServiceConnection {

		public void onServiceConnected(ComponentName name, IBinder boundService) {
			Logger.i(LOGNAME, "onServiceConnected");
			service = IPlaybackService.Stub.asInterface(boundService);
			try {
				state = service.getState();
			} catch (RemoteException e) {
				Logger.e(LOGNAME, "Unable to access state:" + e.getMessage());
			}
			updateUi();
			if (nextUri != null) {
				Logger.i(LOGNAME, "Let's load the saved uri");
				Uri tmpUri = nextUri;
				nextUri = null;
				try {
					service.loadGpx(tmpUri);
				} catch (RemoteException e) {
					Log.w(LOGNAME, "Failed to load the gpx", e);
				}
			}
		}

		public void onServiceDisconnected(ComponentName name) {
			Logger.i(LOGNAME, "onServiceDisconnected");
			service = null;
			updateUi();
		}

	}

	/**
	 * This is called after the file manager finished.
	 */
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);

		switch (requestCode) {
		case REQUEST_FILE:
			if (resultCode == RESULT_OK && data != null) {
				// obtain the filename
				fileUri = data.getData();
				if (fileUri != null) {
					String filePath = fileUri.getPath();
					if (filePath != null) {
						mEditText.setText(filePath);
						saveGpxFilePath(filePath);
					}
				}
				try {
					if (service != null) {
						service.loadGpx(fileUri);
					} else {
						nextUri = fileUri;
					}
				} catch (RemoteException e) {
					Log.w(LOGNAME, "Exception while loading gpx", e);
					return;
				}
			}
			break;
		}
	}

	@Override
	public void onFileLoadStarted() {
		Logger.d(LOGNAME, "File loading started");
		showProgressDialog();
	}

	@Override
	public void onFileLoadFinished() {
		Logger.d(LOGNAME, "File loading finished");
		hideProgressDialog();
		Intent i = new Intent(getApplicationContext(), PlaybackService.class);
		i.putExtra("delayTimeOnReplay", delayTimeOnReplay);
		startService(i);
	}

	@Override
	public void onStatusChange(int newStatus) {
		state = newStatus;
		updateUi();
	}

	@Override
	public void onFileError(String message) {
		hideProgressDialog();
	}

	/**
	 * Saves filepath to private application data saved on disk.
	 * @param filepath
	 */
	public void saveGpxFilePath(String filepath) {

		try {
			FileOutputStream fos = openFileOutput(APP_DATA_CACHE_FILENAME, Context.MODE_PRIVATE);
			if (filepath != null) {
				fos.write(filepath.getBytes());
			} else {
				fos.write(DEFAULT_PATH_TO_GPX_FILE.getBytes());
			}
			fos.close();

		} catch (java.lang.Exception e) {
			Logger.d(LOGNAME, "saveGpxFilePath exception: " + e.getMessage());
		}
	}

	/**
	 * Method gets the path to last GPX file loaded based on value saved in private application
	 * data saved on disk. Defaults to "/".
	 *
	 * @return path to gpx file
	 */
	public String getGpxFilePath() {

		String filepath = DEFAULT_PATH_TO_GPX_FILE;

		try {
			FileInputStream fis = openFileInput(APP_DATA_CACHE_FILENAME);
			filepath = convertStreamToString(fis);
			if (filepath == null || filepath.equalsIgnoreCase("")) {
				filepath = DEFAULT_PATH_TO_GPX_FILE;
			}
			fis.close();

		} catch (java.lang.Exception e) {
			Logger.d(LOGNAME, "getGpxFilePath - no cache file detected - default path being used e.g /");
		}
		return filepath;
	}

	/**
	 * Method reads inputstream to string.
	 *
	 * @param is
	 * @return file contents
	 * @throws Exception
	 */
	public static String convertStreamToString(InputStream is) throws Exception {
		BufferedReader reader = new BufferedReader(new InputStreamReader(is));
		StringBuilder sb = new StringBuilder();
		String line = null;
		while ((line = reader.readLine()) != null) {
			sb.append(line).append("\n");
		}
		reader.close();
		return sb.toString();
	}

}