package org.witness.informacam.transport;

import info.guardianproject.iocipher.File;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;

import net.sqlcipher.database.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.witness.informacam.R;
import org.witness.informacam.crypto.KeyUtility;
import org.witness.informacam.informa.InformaService;
import org.witness.informacam.j3m.J3M;
import org.witness.informacam.j3m.J3M.J3MManifest;
import org.witness.informacam.storage.DatabaseHelper;
import org.witness.informacam.storage.DatabaseService;
import org.witness.informacam.storage.IOUtility;
import org.witness.informacam.transport.HttpUtility.HttpErrorListener;
import org.witness.informacam.utils.Constants;
import org.witness.informacam.utils.MediaHasher;
import org.witness.informacam.utils.Constants.Media;
import org.witness.informacam.utils.Constants.Settings;
import org.witness.informacam.utils.Constants.Storage;
import org.witness.informacam.utils.Constants.Transport;
import org.witness.informacam.utils.Constants.TrustedDestination;
import org.witness.informacam.utils.Constants.Uploader;
import org.witness.informacam.utils.Constants.Media.Manifest;
import org.witness.informacam.utils.Constants.Storage.Tables;

import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap.CompressFormat;
import android.os.Binder;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.provider.BaseColumns;
import android.util.Log;

public class UploaderService extends Service implements HttpErrorListener {
	private final IBinder binder = new LocalBinder();
	private static UploaderService uploaderService;

	DatabaseService databaseService = DatabaseService.getInstance();
	DatabaseHelper dh;
	SQLiteDatabase db;

	TimerTask uploadMonitor;
	Timer t = new Timer();

	Queue<J3MManifest> queue;
	SharedPreferences sp;

	@Override
	public IBinder onBind(Intent intent) {
		return binder;
	}

	public class LocalBinder extends Binder {
		public UploaderService getService() {
			return UploaderService.this;
		}
	}

	public static UploaderService getInstance() {
		return uploaderService;
	}
	
	public void readjustQueue() {
		Log.d(Transport.LOG, "readjusting upload queue, size " + queue.size());
		
		Queue<J3MManifest> queue_ = new LinkedList<J3MManifest>(queue);		
		queue.clear();
		
		for(J3MManifest j3mManifest : queue_) {
			//TODO: queue.add(readjustJ3M(j3mManifest));
			queue.add(j3mManifest);
		}
	}
	
	// TODO: must reinit upload on server
	private J3MManifest readjustJ3M(J3MManifest j3mManifest) {
		J3M j3m = new J3M(j3mManifest);
		
		ContentValues cv = new ContentValues();
		cv.put(Media.Keys.J3M_MANIFEST, j3m.j3mmanifest.toString());
		
		dh.setTable(db, Tables.Keys.MEDIA);
		try {
			db.update(dh.getTable(), cv, Media.Keys.J3M_BASE + "=?", new String[] {j3mManifest.getString(Media.Keys.J3M_BASE)});
			return j3m.j3mmanifest;
		} catch (JSONException e) {
			Log.e(Transport.LOG, e.toString());
			e.printStackTrace();
			return null;
		}
	}

	public int getMode() {
		if(sp == null)
			sp = PreferenceManager.getDefaultSharedPreferences(this);
		try {
			return sp.getInt(Settings.Uploader.MODE, Constants.J3M.Chunks.WHOLE);
		} catch(ClassCastException e) {
			return Integer.parseInt(sp.getString(Settings.Uploader.MODE, String.valueOf(Constants.J3M.Chunks.WHOLE)));
		}
	}

	public void setMode(int mode) {
		if(sp == null)
			sp = PreferenceManager.getDefaultSharedPreferences(this);

		sp.edit().putInt(Settings.Uploader.MODE, mode).commit();
	}
	
	private void clearUploads() {
		dh.setTable(db, Tables.Keys.MEDIA);
		Cursor uploads = dh.getValue(db, null, null, null);
		if(uploads != null && uploads.moveToFirst()) {
			while(!uploads.isAfterLast()) {
				dh.removeValue(db, new String[] {BaseColumns._ID}, new Object[] {uploads.getLong(uploads.getColumnIndex(BaseColumns._ID))});
				uploads.moveToNext();
			}
			uploads.close();
		}
	}

	private void initUploadsFromDatabase() {
		dh.setTable(db, Tables.Keys.MEDIA);
		Cursor uploads = dh.getValue(db, new String[] {Media.Keys.J3M_MANIFEST}, null, null);
		if(uploads != null && uploads.moveToFirst()) {
			Log.d(Transport.LOG, uploads.getColumnCount() + " uploads.");
			while(!uploads.isAfterLast()) {
				byte[] jmd = null;
				try {
					jmd = uploads.getBlob(uploads.getColumnIndex(Media.Keys.J3M_MANIFEST));
				} catch(Exception e) {
					Log.e(Transport.LOG, e.toString());
					e.printStackTrace();
					uploads.moveToNext();
					continue;
				}
				
				
				
				if(jmd == null) {
					uploads.moveToNext();
					continue;
				}
				
				try {
					final J3MManifest j3mManifest = new J3MManifest((JSONObject) new JSONTokener(new String(jmd)).nextValue());

					new Thread(new Runnable() {
						@Override
						public void run() {
							try {
								queue.add(j3mManifest);
							} catch(NullPointerException e) {
								Log.e(Transport.LOG, e.toString());
							}
						}
					}).start();
				} catch (JSONException e) {
					Log.e(Transport.LOG, e.toString());
					e.printStackTrace();
				}
				uploads.moveToNext();
			}
			uploads.close();
		}
	}

	private List<Integer> checkForMissingUploads(J3MManifest j3mManifest) throws JSONException {
		List<Integer> missing = new ArrayList<Integer>();

		Map<String, Object> postData = new HashMap<String, Object>();
		postData.put(Uploader.Keys.AUTH_TOKEN, j3mManifest.getString(Media.Manifest.Keys.AUTH_TOKEN));
		postData.put(Uploader.Keys.CLIENT_PGP, j3mManifest.getString(Uploader.Keys.CLIENT_PGP));
		postData.put(Uploader.Keys.CHECK_FOR_MISSING_TORRENTS, j3mManifest.getString(Media.Keys.J3M_BASE));

		String url = j3mManifest.getString(Transport.Keys.URL);
		long pkcs12Id = j3mManifest.getLong(Transport.Keys.CERTS);

		String result = HttpUtility.executeHttpsPost(UploaderService.this, url, postData, Transport.MimeTypes.TEXT, pkcs12Id, null, null, null);
		JSONObject res = parseResult(result);
		if(res.getString(Transport.Keys.RESULT).equals(Transport.Result.OK)) {
			JSONArray m = res.getJSONArray(Transport.Keys.MISSING_TORRENTS);
			for(int i=0; i<m.length(); i++)
				missing.add(m.getInt(i));
		} else {
			// handle not having a result with some silly trickery
			missing.add(-1 * res.getInt(Transport.Keys.ERROR_CODE));
		}

		return missing;
	}

	public void requestTicket(J3MManifest manifest) {
		Map<String, Object> postData = new HashMap<String, Object>();

		try {
			postData.put(Uploader.Keys.J3M, manifest.getJSONObject(Constants.J3M.Keys.DESCRIPTOR).toString());
			String result = HttpUtility.executeHttpsPost(this, manifest.getString(Constants.J3M.Keys.URL), postData, Transport.MimeTypes.JSON, manifest.getLong(Constants.J3M.Keys.PKCS12_ID), null, null, null);
			Log.d(Transport.LOG, result);
			
			JSONObject res = parseResult(result);
			String authToken = res.getJSONObject(Transport.Keys.BUNDLE).getString(Media.Manifest.Keys.AUTH_TOKEN);

			manifest.put(Constants.J3M.Keys.AUTH_TOKEN, authToken);
			manifest.save();

			queue.add(manifest);
		} catch (JSONException e) {
			Log.e(Transport.LOG, e.toString());
			e.printStackTrace();
		} catch(NullPointerException e) {
			Log.e(Transport.LOG, e.toString());
			e.printStackTrace();
		}
	}

	private boolean uploadPatch(J3MManifest j3mManifest, String chunkName) {
		try {
			byte[] chunk = IOUtility.getBytesFromFile(new File(j3mManifest.getString(Transport.Manifest.Keys.J3MBase) + "/j3m/" + chunkName));

			Map<String, Object> postData = new HashMap<String, Object>();
			postData.put(Uploader.Keys.AUTH_TOKEN, j3mManifest.getString(Media.Manifest.Keys.AUTH_TOKEN));
			postData.put(Uploader.Keys.CLIENT_PGP, j3mManifest.getString(Uploader.Keys.CLIENT_PGP));

			String url = j3mManifest.getString(Transport.Keys.URL);
			long pkcs12Id = j3mManifest.getLong(Transport.Keys.CERTS);

			String result = HttpUtility.executeHttpsPost(this, url, postData, Transport.MimeTypes.TEXT, pkcs12Id, chunk, chunkName, Transport.MimeTypes.OCTET_STREAM);

			JSONObject res = parseResult(result);
			if(res != null) {
				// TODO???
			}
			return true;
		} catch(JSONException e) {
			return false;
		}
	}

	@SuppressWarnings("unused")
	private boolean getRequiredData(J3MManifest j3mManifest) {
		try {
			String host = j3mManifest.getString(Transport.Keys.URL);
			long pkcs12Id = j3mManifest.getLong(Transport.Keys.CERTS);

			Map<String, Object> postData = new HashMap<String, Object>();
			postData.put(Transport.Keys.GET_REQUIREMENTS, j3mManifest.getString(Uploader.Keys.CLIENT_PGP));

			String result = HttpUtility.executeHttpsPost(this, host, postData, Transport.MimeTypes.TEXT, pkcs12Id);
			JSONObject res = parseResult(result);

			if(res != null) {
				JSONArray requirements = res.getJSONObject(Transport.Keys.BUNDLE).getJSONArray(Transport.Keys.REQUIREMENTS);
				for(int r=0; r<requirements.length(); r++) {
					switch(requirements.getInt(r)) {
					case Transport.Result.ErrorCodes.BASE_IMAGE_REQUIRED:
						dh.setTable(db, Tables.Keys.SETUP);
						Cursor b = dh.getValue(db, new String[] {Settings.Device.Keys.BASE_IMAGE}, BaseColumns._ID, 1L);
						if(b!= null && b.moveToFirst()) {
							byte[] baseImage = b.getBlob(b.getColumnIndex(Settings.Device.Keys.BASE_IMAGE));
							ByteArrayOutputStream baos = new ByteArrayOutputStream();
							BitmapFactory.decodeByteArray(baseImage, 0, baseImage.length).compress(CompressFormat.JPEG, 100, baos);
							b.close();
							uploadSupportingData(j3mManifest, Transport.Keys.BASE_IMAGE, baos.toByteArray(), "baseImage.jpg");
						} else {
							Log.e(Transport.LOG, "could not get base image");
						}
					case Transport.Result.ErrorCodes.PGP_KEY_REQUIRED:
						dh.setTable(db, Tables.Keys.SETUP);
						Cursor p = dh.getValue(db, new String[] {Settings.Device.Keys.SECRET_KEY}, BaseColumns._ID, 1L);
						if(p != null && p.moveToFirst()) {
							byte[] secretKey = p.getBlob(p.getColumnIndex(Settings.Device.Keys.SECRET_KEY));
							p.close();

							try {
								byte publicKey[] = KeyUtility.extractSecretKey(secretKey).getPublicKey().getEncoded();
								uploadSupportingData(j3mManifest, Transport.Keys.PGP_KEY_ENCODED, publicKey, "publicKey.asc");
							} catch (IOException e) {
								Log.e(Transport.LOG, "could not parse found pgp key\n" + e.toString());
								e.printStackTrace();
							}
						} else {
							Log.e(Transport.LOG, "could not find pgp key");
						}
					}
				}
				return true;
			} else {
				return false;
			}
		} catch(JSONException e) {
			Log.e(Transport.LOG, e.toString());
			e.printStackTrace();
			return false;
		}
	}

	private boolean uploadSupportingData(J3MManifest j3mManifest, String supportingDataType, byte[] chunk, String chunkName) {
		try {
			Map<String, Object> postData = new HashMap<String, Object>();
			postData.put(Uploader.Keys.CLIENT_PGP, j3mManifest.getString(Uploader.Keys.CLIENT_PGP));
			postData.put(Uploader.Keys.SUPPORTING_DATA, supportingDataType);

			String url = j3mManifest.getString(Transport.Keys.URL);
			long pkcs12Id = j3mManifest.getLong(Transport.Keys.CERTS);

			String result = HttpUtility.executeHttpsPost(this, url, postData, Transport.MimeTypes.TEXT, pkcs12Id, chunk, chunkName, Transport.MimeTypes.OCTET_STREAM);

			JSONObject res = parseResult(result);
			if(res != null) {
				return true;
			} else
				return false;
		} catch(JSONException e) {
			return false;
		}
	}

	@SuppressWarnings("null")
	private boolean uploadWhole(J3MManifest j3mManifest) {
		byte[] file = null;
		String filename = Transport.Manifest.Keys.J3MBase + "/original";

		String url = null;
		long pkcs12Id = 0;

		Map<String, Object> postData = new HashMap<String, Object>();

		try {
			url = j3mManifest.getString(Transport.Keys.URL);
			pkcs12Id = j3mManifest.getLong(Constants.J3M.Keys.PKCS12_ID);

			postData.put(Uploader.Keys.AUTH_TOKEN, j3mManifest.getString(Media.Manifest.Keys.AUTH_TOKEN));
			postData.put(Uploader.Keys.CLIENT_PGP, j3mManifest.getString(Uploader.Keys.CLIENT_PGP));
			postData.put(Uploader.Keys.WHOLE_UPLOAD, true);
		} catch(JSONException e) {
			Log.e(Transport.LOG, e.toString());
			e.printStackTrace();
			return false;
		}


		try {
			file = IOUtility.getBytesFromFile(new File(j3mManifest.getString(Media.Manifest.Keys.WHOLE_UPLOAD_PATH)));
		} catch(Exception e) {
			Log.d(Storage.LOG, "this error to match\n" + e.getMessage());
			try {
				j3mManifest.put(Manifest.Keys.SHOULD_RETRY, false);
				j3mManifest.save();
			} catch(JSONException e1) {}

			return false;

		}

		if(file == null) {
			Log.d(Transport.LOG, "file is null. exiting.");
			return false;
		}

		String result = HttpUtility.executeHttpsPost(this, url, postData, Transport.MimeTypes.TEXT, pkcs12Id, file, filename, Transport.MimeTypes.OCTET_STREAM);
		Log.d(Transport.LOG, result);
		
		JSONObject res = parseResult(result);

		try {
			if(res != null) {
				// if its already in the queue, pull it out

				if(res.getString(Transport.Keys.RESULT).equals(Transport.Result.OK)) {
					if(queue.contains(j3mManifest)) {
						queue.remove(j3mManifest);
					}

					j3mManifest.save();

					JSONObject bundle = res.getJSONObject(Transport.Keys.BUNDLE);
					Log.d(Transport.LOG, bundle.toString());
				}
			} else {
				switch(res.getInt(Transport.Keys.ERROR_CODE)) {
				case Transport.Result.ErrorCodes.DUPLICATE_J3M_TORRENT:
					//j3mManifest.put(name, false);
					if(queue.contains(j3mManifest))
						queue.remove(j3mManifest);

					break;
				}

			}

			return true;
		} catch(JSONException e) {
			return false;
		}

	}

	@SuppressWarnings("null")
	private boolean uploadChunk(J3MManifest j3mManifest) {
		try {

			int lastTransferred = j3mManifest.getInt(Transport.Manifest.Keys.LAST_TRANSFERRED);

			String chunkName = (lastTransferred + 1) + "_.j3mtorrent";
			byte[] chunk = null;

			if((j3mManifest.getInt(Transport.Manifest.Keys.TOTAL_CHUNKS) -1) != lastTransferred) {
				try {
					chunk = IOUtility.getBytesFromFile(new File(j3mManifest.getString(Transport.Manifest.Keys.J3MBase) + "/j3m/" + chunkName));
				} catch(Exception e) {
					Log.d(Storage.LOG, "this error to match\n" + e.getMessage());
					j3mManifest.put(Manifest.Keys.SHOULD_RETRY, false);
					j3mManifest.save();
					return false;

				}

				if(chunk == null)
					return false;

				Map<String, Object> postData = new HashMap<String, Object>();
				postData.put(Uploader.Keys.AUTH_TOKEN, j3mManifest.getString(Media.Manifest.Keys.AUTH_TOKEN));
				postData.put(Uploader.Keys.CLIENT_PGP, j3mManifest.getString(Uploader.Keys.CLIENT_PGP));

				String url = j3mManifest.getString(Transport.Keys.URL);
				long pkcs12Id = j3mManifest.getLong(Constants.J3M.Keys.PKCS12_ID);

				String result = HttpUtility.executeHttpsPost(this, url, postData, Transport.MimeTypes.TEXT, pkcs12Id, chunk, chunkName, Transport.MimeTypes.OCTET_STREAM);

				JSONObject res = parseResult(result);
				if(res != null) {
					// if its already in the queue, pull it out

					if(res.getString(Transport.Keys.RESULT).equals(Transport.Result.OK)) {
						if(queue.contains(j3mManifest)) {
							queue.remove(j3mManifest);
						}

						JSONObject bundle = res.getJSONObject(Transport.Keys.BUNDLE);
						Log.d(Transport.LOG, bundle.toString());
					}
				} else {
					switch(res.getInt(Transport.Keys.ERROR_CODE)) {
					case Transport.Result.ErrorCodes.DUPLICATE_J3M_TORRENT:
						if(queue.contains(j3mManifest))
							queue.remove(j3mManifest);

						lastTransferred++;
						break;
					}

				}

				// modify the new bytes and add to queue
				j3mManifest.put(Transport.Manifest.Keys.LAST_TRANSFERRED, (lastTransferred + 1));
				j3mManifest.save();

				queue.add(j3mManifest);
				return true;
			} else if(!j3mManifest.has(Media.Manifest.UPLOADED_FLAG) || !j3mManifest.getBoolean(Media.Manifest.UPLOADED_FLAG)){
				List<Integer> missing = checkForMissingUploads(j3mManifest);
				for(int m : missing) {
					if(m > -1) {
						if(uploadPatch(j3mManifest, m + "_.j3mtorrent")) {
							j3mManifest.put(Media.Manifest.UPLOADED_FLAG, true);
							j3mManifest.save();
						}
					} else {
						int ec = -1 * m;
						switch(ec) {
						case Transport.Result.ErrorCodes.AUTH_FAILURE:	// upload was actually completed.
							j3mManifest.put(Media.Manifest.UPLOADED_FLAG, true);
							j3mManifest.save();
							break;
						}
					}
				}
				return true;
			}
		} catch(NullPointerException e) {
			Log.e(Transport.LOG, e.toString());
			e.printStackTrace();
		} catch (JSONException e) {
			Log.e(Transport.LOG, e.toString());
			e.printStackTrace();
		}

		return false;
	}

	private JSONObject parseResult(String result) {
		try {
			JSONObject res = ((JSONObject) new JSONTokener(result).nextValue()).getJSONObject(Transport.Keys.RES);
			return res;
		} catch(JSONException e) {
			try {
				return (JSONObject) new JSONTokener(result).nextValue();
			} catch(JSONException e1) {}
		} catch(ClassCastException e) {
			Log.e(Transport.LOG, "not json but here it is anyway:\n" + result);
		}
		return null;
	}

	long timeIdle = System.currentTimeMillis();

	@Override
	public void onCreate() {
		queue = new LinkedList<J3MManifest>();
		dh = databaseService.getHelper();
		db = databaseService.getDb();

		start();
		uploaderService = this;

	}

	private boolean upload(J3MManifest j3mManifest) {		
		if(!j3mManifest.has(Media.Manifest.Keys.AUTH_TOKEN)) {
			requestTicket(j3mManifest);
			return false;
		}
		
		if(getMode() == Constants.J3M.Chunks.WHOLE) {
			boolean res = uploadWhole(j3mManifest);
			if(res) {
				try {
					j3mManifest.put(Media.Manifest.UPLOADED_FLAG, true);
					j3mManifest.save();
				} catch (JSONException e) {
					e.printStackTrace();
				}
				
			}
			return res;
		} else {
			return uploadChunk(j3mManifest);
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		return START_NOT_STICKY;
	}

	private void saveQueueChanges() {
		Iterator<J3MManifest> qIt = queue.iterator();
		dh.setTable(db, Tables.Keys.MEDIA);
		while(qIt.hasNext()) {
			try {
				J3MManifest j3mManifest = qIt.next();
				ContentValues cv = new ContentValues();
				cv.put(Media.Keys.J3M_MANIFEST, j3mManifest.toString());
				db.update(dh.getTable(), cv, Media.Keys.J3M_BASE + "=?", new String[] {j3mManifest.getString(Media.Keys.J3M_BASE)});
			} catch(JSONException e) {
				Log.e(Transport.LOG, e.toString());
				e.printStackTrace();
			}
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		saveQueueChanges();
		Log.d(Uploader.LOG, "uploader service destroyed.");
	}
	
	public void restart() {
		if(!queue.isEmpty())
			queue.clear();
		
		initUploadsFromDatabase();
	}

	private void start() {
		//this.clearUploads();
				
		if(uploadMonitor == null) {
			uploadMonitor = new TimerTask() {

				@Override
				public void run() {
					if(queue.isEmpty()) {
						return;
					} else {
						Log.d(Transport.LOG, "idle time: " + (System.currentTimeMillis() - timeIdle));
						timeIdle = System.currentTimeMillis();

						J3MManifest j3mManifest = queue.peek();

						if(!upload(j3mManifest)) {
							queue.remove(j3mManifest);
							try {
								if(j3mManifest.has(Manifest.Keys.SHOULD_RETRY) && j3mManifest.getBoolean(Manifest.Keys.SHOULD_RETRY))
									queue.add(j3mManifest);
							} catch(JSONException e) {
								Log.e(Transport.LOG, e.toString());
								e.printStackTrace();
							}
						}
					}
				}
			};
		}
		
		t.schedule(uploadMonitor, 0, 1000);
		initUploadsFromDatabase();
	}

	public static void uploadSupportingData(Map<String, Object> postData, int pkcs12Id, String url) {

	}

	public class UploadNotifier  {
		int percentUploaded = 0;
		int lastUploaded, totalChunks, serialVersionUID;
		String baseName;

		public UploadNotifier(Map<Integer, J3MManifest> jm) throws JSONException {
			Entry<Integer, J3MManifest> entry = jm.entrySet().iterator().next();

			serialVersionUID = entry.getKey();

			lastUploaded = entry.getValue().getInt(Manifest.Keys.LAST_TRANSFERRED) + 1;
			totalChunks = entry.getValue().getInt(Manifest.Keys.TOTAL_CHUNKS);
			baseName = entry.getValue().getString(Manifest.Keys.J3MBASE);
			refreshPercentUploaded();			
		}

		public void updateJ3MUpload(int lastUploaded) {
			this.lastUploaded = lastUploaded;
			refreshPercentUploaded();
		}

		public void setError() {

		}

		private String showPercentUploaded() {
			StringBuffer sb = new StringBuffer();
			sb.append(baseName + ":\n" + percentUploaded + "% " + getString(R.string.upload_service_complete));
			return sb.toString();
		}


		private void refreshPercentUploaded() {
			percentUploaded = (lastUploaded * 100)/totalChunks;
		}
	}

	@Override
	public void onError(Exception e, String msg) {
		Log.e(Transport.LOG, "HEY WE HAVE AN EXCEPTION PASSED\n" + msg);
	}
}