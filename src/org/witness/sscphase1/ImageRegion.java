package org.witness.sscphase1;

import java.io.Serializable;

import net.londatiga.android.ActionItem;
import net.londatiga.android.QuickAction;
import android.graphics.PointF;
import android.graphics.Rect;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnTouchListener;
import android.widget.FrameLayout;

public class ImageRegion extends FrameLayout implements OnTouchListener, OnClickListener, Serializable {

	private static final long serialVersionUID = -244965540057504061L;

	float startX;
	float startY;
	float endX;
	float endY;
	
	PointF startPoint = new PointF();

	int imageWidth;
	int imageHeight;
		
	public static final int NORMAL_MODE = 0;
	public static final int EDIT_MODE = 1;
	public static final int ID_MODE = 2;
	int mode = NORMAL_MODE;
	
	public static final int NOTHING = 0;
	public static final int OBSCURE = 1;
	public static final int ENCRYPT = 2;
	int whattodo = NOTHING;
	
	private ImageEditor imageEditor;
	
	QuickAction qa;
	
	public static final String SSC = "[Camera Obscura : ImageRegion] **************************** ";
	public static final String LOGTAG = SSC;
	//public ImageRegion(Context context, String jsonVersion) {
		// Implement this from JSON
		//this(context, _scaledStartX, _scaledStartY, _scaledEndX, _scaledEndY, _scaledImageWidth, _scaledImageHeight, _imageWidth, _imageHeight, _backgroundColor);	
	//}
	
	View topLeftCorner;
	View topRightCorner;
	View bottomLeftCorner;
	View bottomRightCorner;

	ActionItem editAction;
	ActionItem idAction;
	ActionItem encryptAction;
	ActionItem destroyAction;
				
	public ImageRegion(
			ImageEditor imageEditor, 
			int _scaledStartX, int _scaledStartY, 
			int _scaledEndX, int _scaledEndY, 
			int _scaledImageWidth, int _scaledImageHeight, 
			int _imageWidth, int _imageHeight, 
			int _backgroundColor) 
	{
		super(imageEditor);
		
		this.imageEditor = imageEditor;
		
		/*
		original 300
		current 100
		scaled x 20
		real x 60
		original/current * scaled = real
		
		scaled = real * current/original
		*/
		
		startX = (float)_imageWidth/(float)_scaledImageWidth * (float)_scaledStartX;
		startY = (float)_imageHeight/(float)_scaledImageHeight * (float)_scaledStartY;
		endX = (float)_imageWidth/(float)_scaledImageWidth * (float)_scaledEndX;
		endY = (float)_imageHeight/(float)_scaledImageHeight * (float)_scaledEndY;
		
		Log.v(LOGTAG,"startX: " + startX);
		Log.v(LOGTAG,"startY: " + startY);
		Log.v(LOGTAG,"endX: " + endX);
		Log.v(LOGTAG,"endY: " + endY);
				
		imageWidth = _imageWidth;
		imageHeight = _imageHeight;
		
		setBackgroundColor(_backgroundColor);
		
		inflatePopup();

		this.setOnClickListener(this);
		this.setOnTouchListener(this);
		
		// Inflate Layout
		LayoutInflater inflater = LayoutInflater.from(imageEditor);        
        View innerView = inflater.inflate(R.layout.imageregioninner, null);
        
        topLeftCorner = innerView.findViewById(R.id.TopLeftCorner);
        topRightCorner = innerView.findViewById(R.id.TopRightCorner);
        bottomLeftCorner = innerView.findViewById(R.id.BottomLeftCorner);
        bottomRightCorner = innerView.findViewById(R.id.BottomRightCorner);

		topLeftCorner.setVisibility(View.GONE);
		topRightCorner.setVisibility(View.GONE);
		bottomLeftCorner.setVisibility(View.GONE);
		bottomRightCorner.setVisibility(View.GONE);

		topLeftCorner.setOnTouchListener(this);
		topRightCorner.setOnTouchListener(this);
		bottomLeftCorner.setOnTouchListener(this);
		bottomRightCorner.setOnTouchListener(this);		
		
        this.addView(innerView);		
	}
	
	private void inflatePopup() {
		qa = new QuickAction(this);
		
		editAction = new ActionItem();
		editAction.setTitle("Edit");
		editAction.setIcon(this.getResources().getDrawable(R.drawable.ic_context_edit));
		editAction.setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				qa.dismiss();
				ImageRegion.this.changeMode(EDIT_MODE);
			}
		});
		qa.addActionItem(editAction);

		idAction = new ActionItem();
		idAction.setTitle("ID");
		idAction.setIcon(this.getResources().getDrawable(R.drawable.ic_context_id));
		idAction.setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				qa.dismiss();
				ImageRegion.this.changeMode(ID_MODE);
				imageEditor.launchIdTagger(ImageRegion.this.toString());
			}
		});
		qa.addActionItem(idAction);
		
		encryptAction = new ActionItem();
		encryptAction.setTitle("Encrypt");
		encryptAction.setIcon(this.getResources().getDrawable(R.drawable.ic_context_encrypt));
		encryptAction.setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				qa.dismiss();
				whattodo = ENCRYPT;
				imageEditor.launchEncryptTagger(ImageRegion.this.toString());
			}
		});
		qa.addActionItem(encryptAction);
		
		destroyAction = new ActionItem();
		destroyAction.setTitle("Redact");
		destroyAction.setIcon(this.getResources().getDrawable(R.drawable.ic_context_destroy));
		destroyAction.setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				qa.dismiss();
				whattodo = OBSCURE;
			}
		});
		qa.addActionItem(destroyAction);
	}
	
	
	    
	public void changeMode(int newMode) {
		mode = newMode;
		if (mode == EDIT_MODE) {
			topLeftCorner.setVisibility(View.VISIBLE);
			topRightCorner.setVisibility(View.VISIBLE);
			bottomLeftCorner.setVisibility(View.VISIBLE);
			bottomRightCorner.setVisibility(View.VISIBLE);
		} else if (mode == NORMAL_MODE) {
			topLeftCorner.setVisibility(View.GONE);
			topRightCorner.setVisibility(View.GONE);
			bottomLeftCorner.setVisibility(View.GONE);
			bottomRightCorner.setVisibility(View.GONE);
		}
	}
	
	public Rect getScaledRect(int _scaledImageWidth, int _scaledImageHeight) {
		
		float scaledStartX = (float)startX * (float)_scaledImageWidth/(float)imageWidth;
		float scaledStartY = (float)startY * (float)_scaledImageHeight/(float)imageHeight;
		float scaledEndX = (float)endX * (float)_scaledImageWidth/(float)imageWidth;
		float scaledEndY = (float)endY * (float)_scaledImageHeight/(float)imageHeight;

		Log.v(LOGTAG,"getScaledRect");
		Log.v(LOGTAG,""+scaledStartX);
		Log.v(LOGTAG,""+scaledStartY);
		Log.v(LOGTAG,""+scaledEndX);
		Log.v(LOGTAG,""+scaledEndY);
		
		return new Rect((int)scaledStartX, (int)scaledStartY, (int)scaledEndX, (int)scaledEndY);
	}
	
	public void setScaledRect(Rect scaledRect) {
		
		startX = (float)scaledRect.left * (float)imageWidth/(float)scaledRect.width();
		startY = (float)scaledRect.top * (float)imageHeight/(float)scaledRect.height();
		endX = (float)scaledRect.right * (float)imageWidth/(float)scaledRect.width();
		endY = (float)scaledRect.bottom * (float)imageHeight/(float)scaledRect.height();
	}
	
	public boolean onTouch(View v, MotionEvent event) {
		
		if (mode == EDIT_MODE) {
			
			if (v == topLeftCorner) {
				// Here we expand
				
			} else if (v == topRightCorner) {
				// Here we expand
				
			} else if (v == bottomLeftCorner) {
				// Here we expand
				
			} else if (v == bottomRightCorner) {
				// Here we expand
				
			} else {
				// Here we move
				Log.v(LOGTAG,"Moving Moving");
				
				switch (event.getAction() & MotionEvent.ACTION_MASK) {
					
					case MotionEvent.ACTION_DOWN:
						break;
					
					case MotionEvent.ACTION_UP:
						break;
					
					case MotionEvent.ACTION_MOVE:
						
						/*						
						int imageScaledWidth = imageEditor.imageView.getWidth();
						int imageScaledHeight = imageEditor.imageView.getHeight();

						Log.v(LOGTAG,"imageScaledWidth: "+imageScaledWidth);
						Log.v(LOGTAG,"imageScaledHeight: "+imageScaledHeight);
						
						Rect scaledRect = getScaledRect(imageScaledWidth, imageScaledHeight);
						
						Log.v(LOGTAG,"scaledRect.width: "+scaledRect.width());
						Log.v(LOGTAG,"scaledRect.height: "+scaledRect.height());
						
						scaledRect.left = (int)event.getX() - scaledRect.width()/2;
						scaledRect.top = (int)event.getY() - scaledRect.height()/2;
						
						setScaledRect(scaledRect);
						*/
						
						/*
						 * NOT WORKING IN SCALED STATE
						 */
						int width = (int) (endX - startX);
						int height = (int) (endY - startY);
						startX = event.getX() + width/2;
						endX = width + event.getX() + width/2;
						
						startY = event.getY() + height/2;
						endY = width + event.getY() + height/2;
						
						imageEditor.redrawRegions();
					
						break;
				}
				
			}
			
			return true;
		}
		
		return false;
	}
	
	public void onClick(View v) {
		Log.d(SSC,"CLICKED View " + v.toString());
		if (v == this) {
			/*
			qa = new QuickAction(v);
			for(int x=0;x<aiList.size();x++) {
				qa.addActionItem(aiList.get(x));
			}
			*/
			qa.setAnimStyle(QuickAction.ANIM_REFLECT);
			qa.show();
		}
	}
}
