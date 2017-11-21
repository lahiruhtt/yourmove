package org.nview.yourmove;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;

import android.widget.ImageView;


import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import org.bytedeco.javacpp.opencv_core;
import org.bytedeco.javacpp.opencv_core.IplImage;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;

import java.io.File;
import java.io.IOException;
import java.nio.ShortBuffer;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static org.bytedeco.javacpp.opencv_core.*;
import static org.bytedeco.javacpp.opencv_imgcodecs.*;
import static org.bytedeco.javacpp.opencv_imgproc.*;

public class IdentifyActivity extends Activity implements OnClickListener {

    private final static String CLASS_LABEL = "IdentityActivity";
    private final static String LOG_TAG = CLASS_LABEL;
    /* The number of seconds in the continuous record loop (or 0 to disable loop). */
    final int RECORD_LENGTH = 2;
    /* layout setting */
    private final int bg_screen_bx = 232;
    private final int bg_screen_by = 128;
    private final int bg_screen_width = 700;
    private final int bg_screen_height = 500;
    private final int bg_width = 1123;
    private final int bg_height = 715;
    private final int live_width = 640;
    private final int live_height = 480;
    long startTime = 0;
    boolean training = false;
    Frame[] images;
    long[] timestamps;
    ShortBuffer[] samples;
    int imagesIndex, samplesIndex;
    private PowerManager.WakeLock mWakeLock;
    private File ffmpeg_link = new File(Environment.getExternalStorageDirectory(), "stream.mp4");
//    private FFmpegFrameRecorder recorder;
    private boolean isPreviewOn = false;
    private int imageWidth = 320;
    private int imageHeight = 240;
    private int frameRate = 8;

    /* video data getting thread */
    private Camera cameraDevice;
    private CameraView cameraView;
    private Frame yuvImage = null;
    private int screenWidth, screenHeight;
    private Button btnTrainingControl;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        setContentView(R.layout.activity_identify);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, CLASS_LABEL);
        mWakeLock.acquire();

        initLayout();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if(mWakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, CLASS_LABEL);
            mWakeLock.acquire();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        if(mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        training = false;

        if(cameraView != null) {
            cameraView.stopPreview();
        }

        if(cameraDevice != null) {
            cameraDevice.stopPreview();
            cameraDevice.release();
            cameraDevice = null;
        }

        if(mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
        }
    }

    private void initLayout() {

        /* get size of screen */
        Display display = ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        screenWidth = display.getWidth();
        screenHeight = display.getHeight();
        RelativeLayout.LayoutParams layoutParam = null;
        LayoutInflater myInflate = null;
        myInflate = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        RelativeLayout topLayout = new RelativeLayout(this);
        setContentView(topLayout);
        LinearLayout preViewLayout = (LinearLayout) myInflate.inflate(R.layout.activity_identify, null);
        layoutParam = new RelativeLayout.LayoutParams(screenWidth, screenHeight);
        topLayout.addView(preViewLayout, layoutParam);

        /* add control button: start and stop */
        btnTrainingControl = (Button) findViewById(R.id.identifier_control);
        btnTrainingControl.setText("Start");
        btnTrainingControl.setOnClickListener(this);

        /* add camera view */
        int display_width_d = (int) (1.0 * bg_screen_width * screenWidth / bg_width);
        int display_height_d = (int) (1.0 * bg_screen_height * screenHeight / bg_height);
        int prev_rw, prev_rh;
        if(1.0 * display_width_d / display_height_d > 1.0 * live_width / live_height) {
            prev_rh = display_height_d;
            prev_rw = (int) (1.0 * display_height_d * live_width / live_height);
        } else {
            prev_rw = display_width_d;
            prev_rh = (int) (1.0 * display_width_d * live_height / live_width);
        }
        layoutParam = new RelativeLayout.LayoutParams(prev_rw, prev_rh);
        layoutParam.topMargin = (int) (1.0 * bg_screen_by * screenHeight / bg_height);
        layoutParam.leftMargin = (int) (1.0 * bg_screen_bx * screenWidth / bg_width);
        int cameraType = 1; // front
        cameraDevice = Camera.open(cameraType);
        Log.i(LOG_TAG, "cameara open");
        cameraView = new CameraView(this, cameraDevice);
        topLayout.addView(cameraView, layoutParam);
        Log.i(LOG_TAG, "cameara preview start: OK");
    }

    //---------------------------------------
    // initialize ffmpeg_recorder
    //---------------------------------------
    private void initTrainer() {

        Log.w(LOG_TAG, "init trainer");

        if(RECORD_LENGTH > 0) {
            imagesIndex = 0;
            images = new Frame[RECORD_LENGTH * frameRate];
            timestamps = new long[images.length];
            for(int i = 0; i < images.length; i++) {
                images[i] = new Frame(imageWidth, imageHeight, Frame.DEPTH_UBYTE, 2);
                timestamps[i] = -1;
            }
        } else if(yuvImage == null) {
            yuvImage = new Frame(imageWidth, imageHeight, Frame.DEPTH_UBYTE, 2);
            Log.i(LOG_TAG, "create yuvImage");
        }
        Log.i(LOG_TAG, "Trainer initialize success");

    }

    public void startTraining() {

        initTrainer();
        startTime = System.currentTimeMillis();
        training = true;
    }


    @Override
    public void onClick(View v) {
        if(!training) {
            startTraining();
            Log.w(LOG_TAG, "Start Button Pushed");
            btnTrainingControl.setText("Stop");
        } else {
            // This will trigger the  training loop to stop and then set isRecorderStart = false;
            training = false;
            Log.w(LOG_TAG, "Stop Button Pushed");
            btnTrainingControl.setText("Start");
        }

    }

    //---------------------------------------------
    // camera thread, gets and encodes video data
    //---------------------------------------------
    class CameraView extends SurfaceView implements SurfaceHolder.Callback, PreviewCallback {

        private SurfaceHolder mHolder;
        private Camera mCamera;

        public CameraView(Context context, Camera camera) {
            super(context);
            Log.w("camera", "camera view");
            mCamera = camera;
            mHolder = getHolder();
            mHolder.addCallback(CameraView.this);
            mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
            mCamera.setPreviewCallback(CameraView.this);
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            try {
                stopPreview();
                mCamera.setPreviewDisplay(holder);
            } catch(IOException exception) {
                mCamera.release();
                mCamera = null;
            }
        }

        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Camera.Parameters camParams = mCamera.getParameters();
            List<Camera.Size> sizes = camParams.getSupportedPreviewSizes();
            // Sort the list in ascending order
            Collections.sort(sizes, new Comparator<Camera.Size>() {

                public int compare(final Camera.Size a, final Camera.Size b) {
                    return a.width * a.height - b.width * b.height;
                }
            });

            // Pick the first preview size that is equal or bigger, or pick the last (biggest) option if we cannot
            // reach the initial settings of imageWidth/imageHeight.
            for(int i = 0; i < sizes.size(); i++) {
                if((sizes.get(i).width >= imageWidth && sizes.get(i).height >= imageHeight) || i == sizes.size() - 1) {
                    imageWidth = sizes.get(i).width;
                    imageHeight = sizes.get(i).height;
                    Log.v(LOG_TAG, "Changed to supported resolution: " + imageWidth + "x" + imageHeight);
                    break;
                }
            }
            camParams.setPreviewSize(imageWidth, imageHeight);
            camParams.setRotation(270);


            Log.v(LOG_TAG, "Setting imageWidth: " + imageWidth + " imageHeight: " + imageHeight + " frameRate: " + frameRate);

            camParams.setPreviewFrameRate(frameRate);
            Log.v(LOG_TAG, "Preview Framerate: " + camParams.getPreviewFrameRate());

            mCamera.setParameters(camParams);
            startPreview();
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            try {
                mHolder.addCallback(null);
                mCamera.setPreviewCallback(null);
            } catch(RuntimeException e) {
                // The camera has probably just been released, ignore.
            }
        }

        public void startPreview() {
            if(!isPreviewOn && mCamera != null) {
                isPreviewOn = true;
                mCamera.startPreview();
            }
        }

        public void stopPreview() {
            if(isPreviewOn && mCamera != null) {
                isPreviewOn = false;
                mCamera.stopPreview();
            }
        }

        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {
            if(images == null) {
                startTime = System.currentTimeMillis();
                return;
            }
            if(RECORD_LENGTH > 0) {
                int i = imagesIndex++ % images.length;
                yuvImage = images[i];
                timestamps[i] = 1000 * (System.currentTimeMillis() - startTime);
            }


            OpenCVFrameConverter.ToIplImage toIplImage = new OpenCVFrameConverter.ToIplImage();
            IplImage iplImage = toIplImage.convert(yuvImage);
            IplImage imgThreshold = getThresholdImage(iplImage);




//            tariningLayout(toIplImage.convert(imgThreshold));
            // Show threshold

//            Bitmap bitmap = Bitmap.createBitmap(imageWidth, imageHeight,Bitmap.Config.RGB_565);
//            bitmap.copyPixelsFromBuffer(imgThreshold.getByteBuffer());



        }
    }


    private IplImage getThresholdImage(IplImage orgImg) {

        //color range of red like color
        opencv_core.CvScalar min = cvScalar(0, 0, 130, 0);//BGR-A
        opencv_core.CvScalar max= cvScalar(140, 110, 255, 0);//BGR-A

        IplImage imgThreshold = cvCreateImage(cvGetSize(orgImg), 8, 1);
        //
        cvInRangeS(orgImg, min, max, imgThreshold);// red

        cvSmooth(imgThreshold, imgThreshold, CV_MEDIAN, 15,0,0,0);

        return imgThreshold;
    }

//    private void tariningLayout(Frame imgThreshold){
//
//
//        /* get size of screen */
//        Display display = ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
//        screenWidth = display.getWidth();
//        screenHeight = display.getHeight();
//        RelativeLayout.LayoutParams layoutParam = null;
//        LayoutInflater myInflate = null;
//        myInflate = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
//        RelativeLayout topLayout = new RelativeLayout(this);
//        setContentView(topLayout);
//        LinearLayout preViewLayout = (LinearLayout) myInflate.inflate(R.layout.activity_identify, null);
//        layoutParam = new RelativeLayout.LayoutParams(screenWidth, screenHeight);
//        topLayout.addView(preViewLayout, layoutParam);
//
//        /* add control button: start and stop */
////        btnTrainingControl = (Button) findViewById(R.id.identifier_control);
//        btnTrainingControl.setText("Stop");
//        btnTrainingControl.setOnClickListener(this);
//
//        /* add camera view */
//        int display_width_d = (int) (1.0 * bg_screen_width * screenWidth / bg_width);
//        int display_height_d = (int) (1.0 * bg_screen_height * screenHeight / bg_height);
//        int prev_rw, prev_rh;
//        if(1.0 * display_width_d / display_height_d > 1.0 * live_width / live_height) {
//            prev_rh = display_height_d;
//            prev_rw = (int) (1.0 * display_height_d * live_width / live_height);
//        } else {
//            prev_rw = display_width_d;
//            prev_rh = (int) (1.0 * display_width_d * live_height / live_width);
//        }
//        layoutParam = new RelativeLayout.LayoutParams(prev_rw, prev_rh);
//        layoutParam.topMargin = (int) (1.0 * bg_screen_by * screenHeight / bg_height);
//        layoutParam.leftMargin = (int) (1.0 * bg_screen_bx * screenWidth / bg_width);
//        int cameraType = 1; // front
//        cameraDevice = Camera.open(cameraType);
//        Log.i(LOG_TAG, "cameara open");
//        cameraView = new CameraView(this, cameraDevice);
//        topLayout.addView()
//        Log.i(LOG_TAG, "cameara preview start: OK");
//    }
}
