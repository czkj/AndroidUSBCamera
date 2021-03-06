package com.jiangdg.usbcamera;

import android.app.Activity;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.os.Environment;

import com.jiangdg.libusbcamera.R;
import com.serenegiant.usb.DeviceFilter;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.common.AbstractUVCCameraHandler;
import com.serenegiant.usb.common.UVCCameraHandler;
import com.serenegiant.usb.encoder.RecordParams;
import com.serenegiant.usb.widget.CameraViewInterface;

import java.io.File;
import java.util.List;

/**USB摄像头工具类
 *
 * Created by jiangdongguo on 2017/9/30.
 */

public class USBCameraManager{
    public static final String ROOT_PATH = Environment.getExternalStorageDirectory().getAbsolutePath()
            + File.separator;
    public static final String SUFFIX_PNG = ".png";
    public static final String SUFFIX_MP4 = ".mp4";
    private static final String TAG = "USBCameraManager";
    private int previewWidth = 640;
    private int previewHeight = 480;
    // 使用MediaVideoBufferEncoder
    private static final int ENCODER_TYPE = 2;
    //0为YUYV，1为MJPEG
    private static final int PREVIEW_FORMAT = 0;

    private static USBCameraManager mUsbCamManager;
    // USB设备管理类
    private USBMonitor mUSBMonitor;
    // Camera业务逻辑处理
    private UVCCameraHandler mCameraHandler;

    private Context mContext;
    private USBMonitor.UsbControlBlock mCtrlBlock;

    private USBCameraManager(){}

    public static USBCameraManager getInstance(){
        if(mUsbCamManager == null){
            mUsbCamManager = new USBCameraManager();
        }
        return mUsbCamManager;
    }

    public interface OnMyDevConnectListener{
        void onAttachDev(UsbDevice device);
        void onDettachDev(UsbDevice device);
        void onConnectDev(UsbDevice device,boolean isConnected);
        void onDisConnectDev(UsbDevice device);
    }

    public interface OnPreviewListener{
        void onPreviewResult(boolean isSuccess);
    }

    /** 初始化
     *
     *  context  上下文
     *  cameraView Camera要渲染的Surface
     *  listener USB设备检测与连接状态事件监听器
     * */
    public void init(Activity activity, final CameraViewInterface cameraView, final OnMyDevConnectListener listener){
        if(cameraView == null)
            throw new NullPointerException("CameraViewInterface cannot be null!");
        mContext = activity.getApplicationContext();

        mUSBMonitor = new USBMonitor(activity.getApplicationContext(), new USBMonitor.OnDeviceConnectListener() {

            // 当检测到USB设备，被回调
            @Override
            public void onAttach(UsbDevice device) {
                if(listener != null){
                    listener.onAttachDev(device);
                }
            }

            // 当拨出或未检测到USB设备，被回调
            @Override
            public void onDettach(UsbDevice device) {
                if(listener != null){
                    listener.onDettachDev(device);
                }
                // 释放资源
                release();
            }

            // 当连接到USB Camera时，被回调
            @Override
            public void onConnect(final UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
                mCtrlBlock = ctrlBlock;
                // 打开摄像头
                openCamera(ctrlBlock);
                // 开启预览
                startPreview(cameraView, new AbstractUVCCameraHandler.OnPreViewResultListener() {
                    @Override
                    public void onPreviewResult(boolean isConnected) {
                        if(listener != null){
                            listener.onConnectDev(device,isConnected);
                        }
                    }
                });
            }

            // 当与USB Camera断开连接时，被回调
            @Override
            public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
                if(listener != null){
                    listener.onDisConnectDev(device);
                }
            }

            @Override
            public void onCancel(UsbDevice device) {
            }
        });
        cameraView.setAspectRatio(previewWidth / (float)previewHeight);
        mCameraHandler = UVCCameraHandler.createHandler(activity,cameraView,ENCODER_TYPE,
                previewWidth,previewHeight,PREVIEW_FORMAT);
    }

    // 切换分辨率
    public void updateResolution(Activity activity, CameraViewInterface cameraView, int width, int height, final OnPreviewListener mPreviewListener){
        // 如果分辨率无变化，则无需重启Camera
        if(previewWidth == width && previewHeight == height){
            return;
        }
        this.previewWidth = width;
        this.previewHeight = height;
        // 关闭摄像头
        closeCamera();
        // 释放CameraHandler占用的相关资源
        if(mCameraHandler != null){
            mCameraHandler.release();
            mCameraHandler = null;
        }
        // 重新初始化mCameraHandler
        cameraView.setAspectRatio(previewWidth / (float)previewHeight);
        mCameraHandler = UVCCameraHandler.createHandler(activity,cameraView,ENCODER_TYPE,
                previewWidth,previewHeight,PREVIEW_FORMAT);
        openCamera(mCtrlBlock);
        // 开始预览
        startPreview(cameraView, new AbstractUVCCameraHandler.OnPreViewResultListener() {
            @Override
            public void onPreviewResult(boolean result) {
                if(mPreviewListener != null){
                    mPreviewListener.onPreviewResult(result);
                }
            }
        });
    }

    /**
     * 注册检测USB设备广播接收器
     * */
    public void registerUSB(){
        if(mUSBMonitor != null){
            mUSBMonitor.register();
        }
    }

    /**
     *  注销检测USB设备广播接收器
     */
    public void unregisterUSB(){
        if(mUSBMonitor != null){
            mUSBMonitor.unregister();
        }
    }

    /**
     *  请求开启第index USB摄像头
     */
    public void requestPermission(int index){
        List<UsbDevice> devList = getUsbDeviceList();
        if(devList==null || devList.size() ==0){
            return;
        }
        int count = devList.size();
        if(index >= count)
            new IllegalArgumentException("index illegal,should be < devList.size()");
        if(mUSBMonitor != null) {
            mUSBMonitor.requestPermission(getUsbDeviceList().get(index));
        }
    }

    /**
     * 返回
     * */
    public int getUsbDeviceCount(){
        List<UsbDevice> devList = getUsbDeviceList();
        if(devList==null || devList.size() ==0){
            return 0;
        }
        return devList.size();
    }

    private List<UsbDevice> getUsbDeviceList(){
        List<DeviceFilter> deviceFilters = DeviceFilter.getDeviceFilters(mContext, R.xml.device_filter);
        if(mUSBMonitor == null || deviceFilters == null)
            return null;
        return mUSBMonitor.getDeviceList(deviceFilters.get(0));
    }

    /**
     * 抓拍照片
     * */
    public void capturePicture(String savePath){
        if(mCameraHandler != null && mCameraHandler.isOpened()){
            mCameraHandler.captureStill(savePath);
        }
    }

    public void startRecording(RecordParams params, AbstractUVCCameraHandler.OnEncodeResultListener listener){
        if(mCameraHandler != null && ! isRecording()){
            mCameraHandler.startRecording(params,listener);
        }
    }


    public void stopRecording(){
        if(mCameraHandler != null && isRecording()){
            mCameraHandler.stopRecording();
        }
    }

    public boolean isRecording(){
        if(mCameraHandler != null){
            return mCameraHandler.isRecording();
        }
        return false;
    }

    public boolean isCameraOpened(){
        if(mCameraHandler != null){
            return mCameraHandler.isOpened();
        }
        return false;
    }



    /**
     * 释放资源
     * */
    public void release(){
        // 关闭摄像头
        closeCamera();
        //释放CameraHandler占用的相关资源
        if(mCameraHandler != null){
            mCameraHandler.release();
            mCameraHandler = null;
        }
        // 释放USBMonitor占用的相关资源
        if(mUSBMonitor != null){
            mUSBMonitor.destroy();
            mUSBMonitor = null;
        }
    }

    public USBMonitor getUSBMonitor() {
        return mUSBMonitor;
    }


    public void closeCamera() {
        if(mCameraHandler != null){
            mCameraHandler.close();
        }
    }

    private void openCamera(USBMonitor.UsbControlBlock ctrlBlock) {
        if(mCameraHandler != null){
            mCameraHandler.open(ctrlBlock);
        }
    }

    public void startPreview(CameraViewInterface cameraView,AbstractUVCCameraHandler.OnPreViewResultListener mPreviewListener) {
        SurfaceTexture st = cameraView.getSurfaceTexture();
        if(mCameraHandler != null){
            mCameraHandler.startPreview(st,mPreviewListener);
        }
    }
}
