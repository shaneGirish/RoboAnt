package uk.ac.ed.insectlab.ant;


import java.util.List;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint3f;
import org.opencv.core.Point;
import org.opencv.core.Point3;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import uk.co.ed.insectlab.ant.R;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.provider.Settings.Global;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

public class CameraFragment extends CardFragment implements CvCameraViewListener2 {

	protected static final String TAG = CameraFragment.class.getSimpleName();

	private static final int DOWNSAMPLE_RATE = 4;

	protected static final int REQ_SEGMENT_CIRCLE = 1;

	private BaseLoaderCallback mLoaderCallback;

	private CroppableCameraView mOpenCvCameraView;

	private boolean mLensFound = false;

	private Lens mLens;

	private Mat mRgbaSmall;

	private int mCircleRadMin;
	private int mCircleRadMax;

	private boolean mSegmenting;

	private int mWidth;

	private int mHeight;

	private Object mPictureLock = new Object();

	interface CameraListener {

		void cameraViewStarted(int width, int height);

		void cameraViewStopped();

		void onLensFound(boolean b);

	}

	CameraListener mCameraListener;

	private Button mSegmentCircle;

	private boolean mShowSegmentButton = true;

	private Mat mRgbaCropped;

	private Mat mRgbaZoomed;

	private Size mLensCropSize;

	private Mat mRgbaMasked;

	private Mat mCircleMat;

	private boolean mSegmentationDebug;

	private Mat mRgbaSave;

	private boolean mCameraViewStarted;

	private EditText mPixelsNumText;

	private Mat mRgbaShrinked;

	private Size mZoomSize;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);


	}

	@Override
	public void onAttach(Activity activity) {
		super.onAttach(activity);
		try {
			mCameraListener = (CameraListener)activity;
		} catch(ClassCastException e) {
			e.printStackTrace();
			throw new RuntimeException("Host activity does not implement listener");
		}

		mLoaderCallback = new BaseLoaderCallback(activity) {
			@Override
			public void onManagerConnected(int status) {
				switch (status) {
				case LoaderCallbackInterface.SUCCESS:
				{
					Log.i(TAG, "OpenCV loaded successfully");
					mOpenCvCameraView.enableView();
				} break;
				default:
				{
					super.onManagerConnected(status);
				} break;
				}
			}
		};
	}



	@Override
	public View onCreateCardView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {

		View view = inflater.inflate(R.layout.fragment_camera, container, false);

		mOpenCvCameraView = (CroppableCameraView) view.findViewById(R.id.camera_preview);
		mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);

		mOpenCvCameraView.setCvCameraViewListener(this);

		mSegmentCircle = (Button)view.findViewById(R.id.btn_segment_circle);

		mPixelsNumText = (EditText)view.findViewById(R.id.pixels_num);

		mPixelsNumText.addTextChangedListener(new TextWatcher() {

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				int pixels = Integer.parseInt(s.toString().trim());
				if (mLensFound) {
					if (pixels == mLensCropSize.height * mLensCropSize.width) {
						return;
					}
					else {
						//					setImagePixelsNum(pixels);
						saveNewImagePixelsNum(pixels);
					}
				}
			}



			@Override
			public void beforeTextChanged(CharSequence s, int start, int count,
					int after) {

			}

			@Override
			public void afterTextChanged(Editable s) {

			}
		});


		if (mShowSegmentButton) {
			mSegmentCircle.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					Intent i = new Intent(getActivity(), SegmentCircleActivity.class);
					startActivityForResult(i, REQ_SEGMENT_CIRCLE);
				}
			});
		}
		else {
			mSegmentCircle.setVisibility(View.GONE);
		}

		setLabel("Camera");

		return view;
	}

	@Override
	public void onDestroy() {
		if (mOpenCvCameraView != null) {
			mOpenCvCameraView.disableView();
		}
		super.onDestroy();
	}

	@Override
	public void onPause() {
		if (mOpenCvCameraView != null) {
			mOpenCvCameraView.disableView();
		}
		super.onPause();
	}

	@Override
	public void onResume() {
		OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_2_4_6, getActivity(), mLoaderCallback);
		super.onResume();
	}



	@Override
	public void onCameraViewStarted(int width, int height) {
		setStatus(CardStatus.OK);

		mCameraViewStarted = true;

		mWidth = width;
		mHeight = height;

		mCameraListener.cameraViewStarted(mWidth, mHeight);
		int min = Math.min(mWidth, mHeight);
		mZoomSize = new Size(min, min);

		mRgbaSmall = new Mat();
		mRgbaCropped = new Mat();
		mRgbaZoomed = new Mat();
		mRgbaMasked = new Mat();
		mRgbaSave = new Mat();
		mRgbaShrinked = new Mat();

		loadLens();

		if (mSegmentationDebug) {
			mOpenCvCameraView.setCrop(mWidth, mHeight);
		}

		if (mLensFound) {
			mSegmenting = true;
			mCircleMat = Mat.zeros(height, width, CvType.CV_8UC4);
			Core.circle(mCircleMat, new Point(mLens.x, mLens.y), (int)mLens.radius, 
					new Scalar(new double[] {255.0, 255.0, 255.0, 255.0}), -1);
		}

		int pixels = GLOBAL.getSettings().getImagePixelsNum();
		if (pixels == 0) {
			pixels = min * min;
		}
		setImagePixelsNum(pixels);

	}

	@Override
	public void onCameraViewStopped() {
		setStatus(CardStatus.ERROR);
		mCameraViewStarted = false;
		mCameraListener.cameraViewStopped();
	}

	@Override
	public Mat onCameraFrame(CvCameraViewFrame inputFrame) {
		Mat rgba = inputFrame.rgba();

		if (mSegmenting) {
			if (!mLensFound) {
				Imgproc.resize(rgba, mRgbaSmall, new Size(rgba.cols()/DOWNSAMPLE_RATE, rgba.rows()/DOWNSAMPLE_RATE));
				List<Point3> circles = detectCircles(mRgbaSmall, rgba);
				Mat circleMat = Mat.zeros(rgba.rows(), rgba.cols(), rgba.type());

				if (circles.size() > 0) {
					Point3 circle = circles.get(0);
					circle.x = circle.x * DOWNSAMPLE_RATE;
					circle.y = circle.y * DOWNSAMPLE_RATE;
					circle.z = circle.z * DOWNSAMPLE_RATE;
					Core.circle(circleMat, new Point(
							circle.x, circle.y),
							(int)circle.z, 
							new Scalar(new double[] {200.0, 200.0, 200.0, 200.0}), -1);
					Core.bitwise_and(circleMat, rgba, rgba);
					mLensFound = true;
					mLens = new Lens((int)circle.x, (int)circle.y, (int)circle.z);

					mCameraListener.onLensFound(true);

					if (mSegmentationDebug) {
						Core.circle(rgba, new Point(mLens.x, mLens.y), mLens.radius, 
								new Scalar(new double[] {1.0, 200.0, 0.0, 0.0}), 4);
						return rgba; 
					}

					int min = Math.min(mWidth, mHeight);
					mLensCropSize = new Size(min, min);
					mPixelsNumText.setText(min*min + "");
					mOpenCvCameraView.setCrop(min, min);

					Rect rangeRect = new Rect(mLens.x - mLens.radius,
							mLens.y - mLens.radius, mLens.radius*2, mLens.radius*2);
					Log.i(TAG, "rangeRect " + rangeRect.width + " " + rangeRect.height + " " + rgba.cols() + " " + rgba.rows());
					mRgbaCropped = rgba.submat(rangeRect);

					Imgproc.resize(mRgbaCropped, mRgbaShrinked, mLensCropSize);
					Imgproc.resize(mRgbaShrinked, mRgbaZoomed, mZoomSize);
					Imgproc.resize(mRgbaCropped, mRgbaSmall, new Size(mRgbaCropped.cols()/DOWNSAMPLE_RATE, mRgbaCropped.rows()/DOWNSAMPLE_RATE));

					synchronized (mPictureLock) {
						mRgbaShrinked.copyTo(mRgbaSave);
					}

					return mRgbaZoomed;
				}
			}
			else {
				if (mSegmentationDebug) {
					Core.circle(rgba, new Point(mLens.x, mLens.y), mLens.radius, 
							new Scalar(new double[] {1.0, 200.0, 0.0, 0.0}), 4);
					return rgba; 
				}
				Core.bitwise_and(mCircleMat, rgba, mRgbaMasked);

				Rect rangeRect = new Rect(mLens.x - mLens.radius,
						mLens.y - mLens.radius, mLens.radius*2, mLens.radius*2);
				mRgbaCropped = mRgbaMasked.submat(rangeRect);

				Imgproc.resize(mRgbaCropped, mRgbaShrinked, mLensCropSize);
				Imgproc.resize(mRgbaShrinked, mRgbaZoomed, mZoomSize);

				synchronized (mPictureLock) {
					mRgbaShrinked.copyTo(mRgbaSave);
				}

				return mRgbaZoomed;
			}

		}


		return rgba;
	}

	private List<Point3> detectCircles(Mat rgba, Mat output) {
		Mat gray = new Mat();
		Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
		int width = rgba.cols();

		MatOfPoint3f circles = new MatOfPoint3f();

		//		Imgproc.GaussianBlur(gray, gray, new Size(9, 9), 1, 1);
		Imgproc.HoughCircles(gray, circles, Imgproc.CV_HOUGH_GRADIENT, 1, gray.rows()/8, 200, 80, 
				mCircleRadMin, mCircleRadMax);

		for (Point3 p: circles.toList()) {
			Point center = new Point(p.x * DOWNSAMPLE_RATE, p.y * DOWNSAMPLE_RATE);
			Log.i(TAG, "Circle with center " + center.x + " " + center.y);
			Core.circle(output, center, (int)p.z * DOWNSAMPLE_RATE, new Scalar(new double[] {1.0, 200.0, 0.0, 0.0}));
		}

		return circles.toList();

	}

	public void setSegmenting(boolean segmenting) {
		mSegmenting = segmenting;
	}

	public void segmentAgain(int circleRadMin, int circleRadMax) {
		mLensFound = false;
		mCircleRadMin = circleRadMin;
		mCircleRadMax = circleRadMax;
		setSegmenting(true);
	}

	public int getWidth() {
		return mWidth;
	}

	public int getHeight() {
		return mHeight;
	}

	public void showSegmentButton(boolean show) {
		if (show) {
			if (mSegmentCircle != null) {
				mSegmentCircle.setVisibility(View.VISIBLE);
			}
		}
		else {
			if (mSegmentCircle != null) {
				mSegmentCircle.setVisibility(View.GONE);
			}
		}
		mShowSegmentButton = show;
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (requestCode == REQ_SEGMENT_CIRCLE) {
			loadLens();
			if (mLensFound) {
				setSegmenting(true);
			}
		}
		super.onActivityResult(requestCode, resultCode, data);
	}

	private void loadLens() {
		Log.i(TAG, "loading lens");
		Lens lens = GLOBAL.getSettings().loadLens();
		if (lens != null) {
			mLens = lens;
			mLensFound = true;
			mCameraListener.onLensFound(true);
			int min = Math.min(mWidth, mHeight);
			mLensCropSize = new Size(min, min);
			mOpenCvCameraView.setCrop(min, min);
		}
		else {
			mLensFound = false;
			mCameraListener.onLensFound(false);
		}
	}

	public void releaseCamera() {
		mOpenCvCameraView.disconnectCamera();
	}

	public void setSegmentationDebug(boolean b) {
		mSegmentationDebug = b;

	}

	public Lens getLens() {
		return mLens;
	}

	/** Returns null when camera not ready **/
	public Bitmap getPicture() {
		if (!mCameraViewStarted || mRgbaSave.cols() == 0 || mRgbaSave.rows() == 0) {
			Log.i(TAG, "Not ready yet!");
			return null;
		}
		synchronized (mPictureLock) {
			Bitmap bmp = Bitmap.createBitmap(mRgbaSave.cols(), mRgbaSave.rows(), Bitmap.Config.ARGB_8888);
			Utils.matToBitmap(mRgbaSave, bmp);
			return bmp;
		}
	}

	public void setImagePixelsNum(int pixels) {
		int newWidth = (int)Math.sqrt(pixels);
		int newHeight = newWidth;
		//		mOpenCvCameraView.setCrop(newWidth, newHeight);
		mLensCropSize = new Size(newWidth, newHeight);
		mPixelsNumText.setText(newWidth * newHeight + " ");
	}
	public void saveNewImagePixelsNum(int pixels) {
		GLOBAL.getSettings().setImagePixelsNum(pixels);
		setImagePixelsNum(pixels);
	}

	public void fixPixelSize() {
		mPixelsNumText.setEnabled(false);
	}

}
