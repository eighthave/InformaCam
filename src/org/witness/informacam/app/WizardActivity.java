package org.witness.informacam.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import net.sqlcipher.database.SQLiteDatabase;

import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.witness.informacam.R;
import org.witness.informacam.app.adapters.SelectionsAdapter;
import org.witness.informacam.app.mods.InformaButton;
import org.witness.informacam.app.mods.InformaEditText;
import org.witness.informacam.app.mods.InformaSpinner;
import org.witness.informacam.app.mods.InformaTextView;
import org.witness.informacam.app.mods.Selections;
import org.witness.informacam.crypto.CertificateUtility;
import org.witness.informacam.crypto.CertificateUtility.ClientCertificateResponse;
import org.witness.informacam.crypto.KeyUtility;
import org.witness.informacam.crypto.KeyUtility.KeyServerResponse;
import org.witness.informacam.storage.DatabaseHelper;
import org.witness.informacam.storage.DatabaseService;
import org.witness.informacam.storage.IOCipherService;
import org.witness.informacam.storage.IOUtility;
import org.witness.informacam.utils.AddressBookUtility.AddressBookDisplay;
import org.witness.informacam.utils.Constants.App;
import org.witness.informacam.utils.Constants.Crypto;
import org.witness.informacam.utils.Constants.Informa;
import org.witness.informacam.utils.Constants.TrustedDestination;
import org.witness.informacam.utils.Constants.App.Wizard;
import org.witness.informacam.utils.Constants.Crypto.PGP;
import org.witness.informacam.utils.Constants.Settings.Device;
import org.witness.informacam.utils.Constants.Storage.Tables;
import org.witness.informacam.utils.Constants.Settings;
import org.witness.informacam.utils.FormUtility;
import org.witness.informacam.utils.InformaMediaScanner;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

public class WizardActivity extends Activity implements OnClickListener {
	int current;
	int backOffset = 1;
	int nextOffset = 1;

	LinearLayout frame_content, navigation_holder;
	TextView frame_title, progress;
	InformaButton wizard_next, wizard_back, wizard_done;

	private SharedPreferences sp;
	private SharedPreferences.Editor ed;

	WizardForm wizardForm;

	private Intent launchDatabaseService, initVirtualStorage;
	Handler h = new Handler();

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		sp = PreferenceManager.getDefaultSharedPreferences(this);
		ed = sp.edit();

		current = getIntent().getIntExtra("current", 0);

		wizardForm = new WizardForm(this);
		launchDatabaseService = new Intent(this, DatabaseService.class);
		initVirtualStorage = new Intent(WizardActivity.this, IOCipherService.class);

		SQLiteDatabase.loadLibs(this);

		initLayout();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
	}

	private void initLayout() {
		setContentView(R.layout.wizardactivity);

		frame_title = (TextView) findViewById(R.id.wizard_frame_title);

		frame_content = (LinearLayout) findViewById(R.id.wizard_frame_content);
		navigation_holder = (LinearLayout) findViewById(R.id.wizard_navigation_holder);

		wizard_done = (InformaButton) findViewById(R.id.wizard_done);
		wizard_back = (InformaButton) findViewById(R.id.wizard_back);
		wizard_next = (InformaButton) findViewById(R.id.wizard_next);

		if(current < wizardForm.frames.length() - 1)
			wizard_next.setOnClickListener(this);
		else {
			wizard_next.setVisibility(View.GONE);
			wizard_back.setVisibility(View.GONE);

			wizard_done.setVisibility(View.VISIBLE);
			wizard_done.setOnClickListener(this);
		}

		if(current > 0)
			wizard_back.setOnClickListener(this);
		else {
			setMandatory(wizard_back);
		}

		progress = (TextView) findViewById(R.id.wizard_progress);
		progress.setText((current + 1) + "/" + wizardForm.frames.length());

		try {
			initFrame();
		} catch(JSONException e) {
			Log.e(App.LOG, e.toString());
		}
	}

	public void setMandatory(View v) {
		((InformaButton) v).getBackground().setAlpha(100);
		((InformaButton) v).setClickable(false);
	}

	public void enableAction(View v) {
		((InformaButton) v).getBackground().setAlpha(255);
		((InformaButton) v).setClickable(true);
	}

	public void disableAction(View v) {
		((InformaButton) v).getBackground().setAlpha(100);
		((InformaButton) v).setClickable(false);
	}

	public void initFrame() throws JSONException {
		wizardForm.setFrame(current);
		frame_title.setText(wizardForm.getTitle());

		ArrayList<View> views = wizardForm.getContent();
		for(View v : views)
			frame_content.addView(v);
	}

	@SuppressWarnings("unused")
	private void setUserAlias(String alias) {
		ed.putString(Informa.Keys.Device.DISPLAY_NAME, alias).commit();
	}

	@SuppressWarnings("unused")
	private void saveDBPW(String pw) {
		ed.putString(Settings.Keys.CURRENT_LOGIN, pw).commit();
		startService(launchDatabaseService);
	}

	@SuppressWarnings("unused")
	private void setDBPWCache(ArrayList<Selections> cacheSelection) {
		for(Selections s : cacheSelection) {
			if(s.getSelected())
				ed.putString(Settings.Keys.LOGIN_CACHE_TIME, String.valueOf(cacheSelection.indexOf(s) + 200)).commit();
		}
	}

	private void storeDeviceKey() {
		passThrough();
	}

	@SuppressWarnings("unused")
	private void registerDeviceKey() {
		if(KeyUtility.generateNewKeyFromImage(WizardActivity.this)) {
			startService(initVirtualStorage);
			
			h.postDelayed(
				new Runnable() {
					@Override
					public void run() {
						initInstalledAssets();
						passThrough();
					}
				}, 500);
		}
	}

	@SuppressWarnings("unused")
	private void setEncryptionValues(ArrayList<Selections> encryptionSelection) {
		String[] encryptionValues = getResources().getStringArray(R.array.enable_encryption_values);
		for(Selections s: encryptionSelection) {
			if(s.getSelected())
				ed.putBoolean(Settings.Keys.USE_ENCRYPTION, Boolean.parseBoolean(encryptionValues[encryptionSelection.indexOf(s)])).commit();
		}
	}

	@SuppressWarnings("unused")
	private void setDefaultImageHandling(ArrayList<Selections> imageHandlingSelection) {
		for(Selections s : imageHandlingSelection) {
			if(s.getSelected())
				ed.putString(Settings.Keys.DEFAULT_IMAGE_HANDLING, String.valueOf(imageHandlingSelection.indexOf(s) + 300)).commit();
		}
	}

	private void initInstalledAssets() {
		try {
			if(getAssets().list("installedForms").length > 0) {
				for(String form : getAssets().list("installedForms")) {
					Log.d(App.LOG, "initing: " + form);
					FormUtility.importAndParse(WizardActivity.this, getAssets().open("installedForms/" + form));
				}
			}
		} catch (IOException e) {
			Log.e(App.LOG, e.toString());
			e.printStackTrace();
		}

		try {
			String[] allKeys = getAssets().list("installedKeys");
			for(String keyFolder : allKeys) {
				String[] folderContent = getAssets().list("installedKeys/" + keyFolder);
				KeyServerResponse ksr = null;
				byte[] imgBytes = null;
				byte[] certBytes = null;
				byte[] pgpBytes = null;
				String trustedDestinationURL = null;
				String clientPassword = null;
				ClientCertificateResponse ccr = null;

				for(String keyFile : folderContent) {
					String ext = keyFile.substring(keyFile.lastIndexOf("."));

					if(ext.equals(".txt")) {
						BufferedReader br = new BufferedReader(new InputStreamReader(getAssets().open("installedKeys/" + keyFolder + "/" + keyFile)));
						char[] buf = new char[1024];
						int numRead = 0;

						String line;

						while((numRead = br.read(buf)) != -1) {
							line = String.valueOf(buf, 0, numRead);

							String[] lines = line.split(";");
							for(String l : lines) {
								String key = l.split("=")[0];
								String value = l.split("=")[1];

								if(key.equals(TrustedDestination.Keys.URL))
									trustedDestinationURL = value;
								if(key.equals(Crypto.Keystore.Keys.PASSWORD))
									clientPassword = value;

								// TODO: if we add other key-values to the txt file, they can be caught here...
							}

							buf = new char[1024];
						}

						br.close();

					} else {
						InputStream is = getAssets().open("installedKeys/" + keyFolder + "/" + keyFile);
						if(ext.equals(".png") || ext.equals(".jpg")) {

							imgBytes = new byte[is.available()];
							is.read(imgBytes);

							imgBytes = Base64.encode(imgBytes, Base64.DEFAULT);
						} else if(ext.equals(".p12")) {
							certBytes = new byte[is.available()];
							is.read(certBytes);

							certBytes = Base64.encode(certBytes, Base64.DEFAULT);
						} else if(ext.equals(".asc")) {
							pgpBytes = new byte[is.available()];
							is.read(pgpBytes);

							pgpBytes = Base64.encode(pgpBytes, Base64.DEFAULT);

							PGPPublicKey key = KeyUtility.extractPublicKeyFromBytes(pgpBytes);
							ksr = new KeyUtility.KeyServerResponse(key);
						}

						AddressBookDisplay abd = new AddressBookDisplay(WizardActivity.this, 0L, ksr.getString(PGP.Keys.PGP_DISPLAY_NAME), ksr.getString(PGP.Keys.PGP_EMAIL_ADDRESS), imgBytes, false);
						abd.put(TrustedDestination.Keys.URL, trustedDestinationURL);

						KeyUtility.installNewKey(WizardActivity.this, ksr, abd);

						ccr = new ClientCertificateResponse(certBytes, trustedDestinationURL, ksr.getLong(PGP.Keys.PGP_KEY_ID), clientPassword);
						CertificateUtility.storeClientCertificate(WizardActivity.this, ccr, certBytes);
					}
				}
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (PGPException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}


	}

	@SuppressWarnings("unused")
	private String[] getDefaultImageHandlingOptions() {
		return getResources().getStringArray(R.array.default_image_handling);
	}

	@SuppressWarnings("unused")
	private String[] getDBPWCacheValues() {
		return getResources().getStringArray(R.array.password_cache);
	}

	@SuppressWarnings("unused")
	private String[] getEncryptionValues() {
		return getResources().getStringArray(R.array.enable_encryption);
	}

	@SuppressWarnings("unused")
	private void initDeviceKey() {    	
		startActivityForResult(new Intent(this, SurfaceGrabberActivity.class), App.Wizard.FROM_BASE_IMAGE_CAPTURE);
	}

	@Override
	public void onClick(View v) {
		if(v == wizard_back) {
			if(current > 0) {
				Intent i = new Intent(this, WizardActivity.class);
				i.putExtra("current", current - backOffset);
				startActivity(i);
				finish();
			}
		} else if(v == wizard_next) {
			if(current < wizardForm.frames.length() - 1) {
				// do the callbacks...
				for(Callback c: wizardForm.getCallbacks()) {
					try {
						c.doCallback();
					} catch (IllegalAccessException e) {
						Log.e(App.LOG, e.toString());
					} catch (NoSuchMethodException e) {
						Log.e(App.LOG, e.toString());
					} catch (InvocationTargetException e) {
						Log.e(App.LOG, e.toString());
					}
				}

				Intent i = new Intent(this, WizardActivity.class);
				i.putExtra("current", current + nextOffset);
				startActivity(i);
				finish();
			}
		} else if(v == wizard_done) {
			ed.putBoolean(Settings.Keys.SETTINGS_VIEWED, true).commit();

			try {
				this.stopService(launchDatabaseService);
			} catch(NullPointerException e) {}

			try {
				this.stopService(initVirtualStorage);
			} catch(NullPointerException e) {}

			Intent i = new Intent(this, MainActivity.class);
			startActivity(i);
			finish();
		}

	}

	private void passThrough() {
		Intent i = new Intent(this, WizardActivity.class);
		i.putExtra("current", current + 1);
		startActivity(i);
		finish();
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		if(resultCode == Activity.RESULT_OK) {
			switch(requestCode) {
			case Wizard.FROM_BASE_IMAGE_CAPTURE:
				storeDeviceKey();
				break;
			}
		}

	}

	private class WizardForm extends JSONObject {
		Context _c;
		JSONArray frames, order;
		JSONObject currentFrame;
		ArrayList<Callback> callbacks;

		public final static String frameKey = "frameKey";
		public final static String frameTitle = "frameTitle";
		public final static String frameContent = "frameContent";
		public final static String frameOrder = "frameOrder";
		public final static String allFrames = "frames";

		public WizardForm(Context c) {
			_c = c;
			frames = new JSONArray();
			order = new JSONArray();
			callbacks = new ArrayList<Callback>();

			// get the list of files within assets/wizard
			try {
				String[] allFiles = _c.getAssets().list("wizard");
				for(String f : allFiles) {
					// get the file
					BufferedReader br = new BufferedReader(new InputStreamReader(_c.getAssets().open("wizard/" + f)));
					String line;
					StringBuilder sb = new StringBuilder();
					while((line = br.readLine()) != null)
						sb.append(line).append('\n');

					// if the file is not "order.json"
					if(f.equals("order.wizard")) {
						for(String s : sb.toString().split(","))
							order.put(s);
					} else {
						JSONObject frame = new JSONObject();
						frame.put(frameKey, f);
						frame.put(frameTitle, parseAsTitle(f));
						frame.put(frameContent, sb.toString());
						frames.put(frame);	
					}

					br.close();
				}
				this.put(frameOrder, order);
				this.put(allFrames, frames);
			} catch (IOException e) {
				Log.e(App.LOG, e.toString());
			} catch (JSONException e) {
				Log.e(App.LOG, e.toString());
			}			
		}

		private String parseAsTitle(String rawTitle) {
			String[] words = rawTitle.split("_");
			StringBuffer sb = new StringBuffer();
			for(String word : words) {
				sb.append(word.toUpperCase() + " ");
			}

			return sb.toString().substring(0, sb.length() - 1);
		}

		public void setFrame(int which) throws JSONException {
			for(int f=0; f<frames.length(); f++) {
				JSONObject frame = frames.getJSONObject(f);
				if(frame.getString(frameKey).compareTo(order.getString(which)) == 0)
					currentFrame = frame;
			}
		}

		public ArrayList<Callback> getCallbacks() {
			return callbacks;
		}

		private String findKey(String content, String key) {
			if(content.indexOf(key) != -1) {
				String keyTail = content.substring(content.indexOf(key + "="));
				String[] pair = keyTail.substring(0, keyTail.indexOf(";")).split("=");
				return pair[1];
			} else {
				return null;
			}
		}

		private String[] parseArguments(String args) {
			String[] a = null;
			if(args != null) {
				a = args.split(",");
			}
			return a;
		}

		@SuppressWarnings("unused")
		public ArrayList<View> getContent() throws JSONException {
			ArrayList<View> views = new ArrayList<View>();
			String content = currentFrame.getString(frameContent);

			for(final String s : content.split("\n")) {
				if(s.contains("{$")) {
					final String type = findKey(s, "type");
					final String callback = findKey(s, "callback");
					final boolean isMandatory = Boolean.parseBoolean(findKey(s, "mandatory"));
					final String attachTo = findKey(s, "attachTo");

					if(isMandatory)
						WizardActivity.this.setMandatory(wizard_next);

					if(type.compareTo("button") == 0) {
						InformaButton button = new InformaButton(_c);
						button.setText(findKey(s, "text"));

						String[] args = parseArguments(findKey(s, "args"));
						final Callback buttonCall = new Callback(callback, args); 

						button.setOnClickListener(new OnClickListener() {

							@Override
							public void onClick(View v) {
								try {
									buttonCall.doCallback();
								} catch (IllegalAccessException e) {
									Log.e(App.LOG, "wizard error", e);
								} catch (NoSuchMethodException e) {
									Log.e(App.LOG, "wizard error", e);
								} catch (InvocationTargetException e) {
									Log.e(App.LOG, "wizard error", e);
								}
							}

						});
						views.add(button);

					} else if(type.compareTo("spinner") == 0) {
						InformaSpinner spinner = new InformaSpinner(_c);

						String[] args = parseArguments(findKey(s, "args"));
						final Callback spinnerCall = new Callback(callback, args);

						disableAction(wizard_next);
						disableAction(wizard_back);

						new Thread(new Runnable() {

							@Override
							public void run() {
								try {
									spinnerCall.doCallback();
								} catch (IllegalAccessException e) {
									Log.e(App.LOG, "wizard error", e);
								} catch (NoSuchMethodException e) {
									Log.e(App.LOG, "wizard error", e);
								} catch (InvocationTargetException e) {
									Log.e(App.LOG, "wizard error", e);
								}

							}
						}).start();


						views.add(spinner);
					} else if(type.compareTo("input") == 0) {
						InformaEditText edittext = new InformaEditText(_c);

						views.add(edittext);
					} else if(type.compareTo("password") == 0) {
						InformaEditText edittext = new InformaEditText(_c);

						edittext.setInputType(InputType.TYPE_TEXT_VARIATION_PASSWORD);
						edittext.setTransformationMethod(new PasswordTransformationMethod());

						edittext.addTextChangedListener(new TextWatcher() {

							@Override
							public void afterTextChanged(Editable e) {
								String pw = e.toString();
								if(pw.length() == 0) {} 
								else if(isValidatedPassword(pw)){
									enableAction(wizard_next);
									if(callback != null) {
										if(attachTo == null)
											callbacks.add(new Callback(callback, new String[] {pw}));

									}
								} else if(!isValidatedPassword(pw)) {
									disableAction(wizard_next);
								}		
							}

							@Override
							public void beforeTextChanged(CharSequence s,
									int start, int count, int after) {}

							@Override
							public void onTextChanged(CharSequence s,
									int start, int before, int count) {}

						});

						views.add(edittext);
					} else if(type.equals("select_one") || type.equals("select_multi")) {

						ArrayList<Selections> selections = new ArrayList<Selections>();
						ListView lv = new ListView(_c);

						for(String option : findKey(s, "values").split(",")) {
							if(Character.toString(option.charAt(0)).equals("#")) {
								// populate from callback
								Callback populate = new Callback(option.substring(1), null);

								try {
									for(String res : (String[]) populate.doCallback())
										selections.add(new Selections(res, false));

								} catch (IllegalAccessException e) {
									Log.e(App.LOG, "wizard error", e);
								} catch (NoSuchMethodException e) {
									Log.e(App.LOG, "wizard error", e);
								} catch (InvocationTargetException e) {
									Log.e(App.LOG, "wizard error", e);
								}
							} else 
								selections.add(new Selections(option, false));
						}

						callbacks.add(new Callback(callback, new Object[] {selections}));
						lv.setAdapter(new SelectionsAdapter(_c, selections, type));
						views.add(lv);
					} else if(type.equals("simple_list")) {
						ArrayList<String> list = new ArrayList<String>();
						ListView lv = new ListView(_c);

						for(String option : findKey(s, "values").split(",")) {
							if(Character.toString(option.charAt(0)).equals("#")) {
								Callback populate = new Callback(option.substring(1), null);
								try {
									for(String res : (String[]) populate.doCallback())
										list.add(res);
								} catch (IllegalAccessException e) {
									Log.e(App.LOG, "wizard error", e);
								} catch (NoSuchMethodException e) {
									Log.e(App.LOG, "wizard error", e);
								} catch (InvocationTargetException e) {
									Log.e(App.LOG, "wizard error", e);
								}
							}
						}
					}

				} else {
					InformaTextView tv = new InformaTextView(_c);
					tv.setText(s);
					views.add(tv);
				}
			}

			return views;
		}

		public String getTitle() throws JSONException {
			return currentFrame.getString(frameTitle);
		}

		private boolean isValidatedPassword (String password) {
			return password.length() >= 10;
		}
	}

	public class Callback {
		String _func;
		Object[] _args;

		public Callback(String func, Object[] args) {
			_func = func;
			_args = args;
		}

		public Object doCallback() throws  IllegalAccessException, NoSuchMethodException, InvocationTargetException {
			Method method;
			if(_args != null) {
				Class<?>[] paramTypes = new Class[_args.length];

				for(int p=0; p<paramTypes.length; p++)
					paramTypes[p] = _args[p].getClass();

				method = WizardActivity.this.getClass().getDeclaredMethod(_func, paramTypes);
			} else
				method = WizardActivity.this.getClass().getDeclaredMethod(_func, null);

			method.setAccessible(true);
			return method.invoke(WizardActivity.this, _args);
		}
	}
}
