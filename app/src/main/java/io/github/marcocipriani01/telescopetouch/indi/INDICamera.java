package io.github.marcocipriani01.telescopetouch.indi;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.indilib.i4j.Constants;
import org.indilib.i4j.INDIBLOBValue;
import org.indilib.i4j.INDIException;
import org.indilib.i4j.client.INDIBLOBElement;
import org.indilib.i4j.client.INDIBLOBProperty;
import org.indilib.i4j.client.INDIDevice;
import org.indilib.i4j.client.INDINumberElement;
import org.indilib.i4j.client.INDINumberProperty;
import org.indilib.i4j.client.INDIProperty;
import org.indilib.i4j.client.INDIPropertyListener;
import org.indilib.i4j.client.INDISwitchElement;
import org.indilib.i4j.client.INDISwitchProperty;
import org.indilib.i4j.client.INDITextElement;
import org.indilib.i4j.client.INDITextProperty;
import org.indilib.i4j.client.INDIValueException;
import org.indilib.i4j.properties.INDIStandardElement;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.marcocipriani01.telescopetouch.R;
import io.github.marcocipriani01.telescopetouch.TelescopeTouchApp;

public class INDICamera implements INDIPropertyListener {

    private static final String TAG = TelescopeTouchApp.getTag(INDICamera.class);
    private static boolean storagePermissionRequested = false;
    public final INDIDevice device;
    private final Context context;
    private final Handler handler;
    private final Set<CameraListener> listeners = new HashSet<>();
    public volatile INDIBLOBProperty blobP;
    public volatile INDIBLOBElement blobE;
    public volatile INDINumberProperty exposureP;
    public volatile INDINumberElement exposureE;
    public volatile INDISwitchProperty exposurePresetsP;
    public volatile INDISwitchElement[] exposurePresetsE;
    public volatile INDISwitchElement[] availableExposurePresetsE;
    public volatile INDISwitchProperty abortP;
    public volatile INDISwitchElement abortE;
    public volatile INDINumberProperty binningP;
    public volatile INDINumberElement binningXE;
    public volatile INDINumberElement binningYE;
    public volatile INDISwitchProperty isoP;
    public volatile INDISwitchElement[] isoE;
    public volatile INDISwitchProperty uploadModeP;
    public volatile INDISwitchElement uploadLocalE;
    public volatile INDISwitchElement uploadClientE;
    public volatile INDISwitchElement uploadBothE;
    public volatile INDITextProperty uploadSettingsP;
    public volatile INDITextElement uploadDirE;
    public volatile INDITextElement uploadPrefixE;
    public volatile INDISwitchProperty frameTypeP;
    public volatile INDISwitchElement[] frameTypesE;
    private volatile Thread loadingThread = null;
    private volatile INDIBLOBValue queuedValue = null;
    private volatile boolean stretch = false;
    private volatile Bitmap lastBitmap = null;
    private boolean bitmapSaved = false;
    private volatile SaveMode saveMode;
    private int jpgQuality = 100;

    public INDICamera(INDIDevice device, Context context, Handler handler) {
        this.device = device;
        this.context = context;
        this.handler = handler;
    }

    public static boolean isCameraProp(INDIProperty<?> property) {
        return property.getName().startsWith("CCD_");
    }

    private static int findFITSLineValue(String in) {
        if (in.contains("=")) in = in.split("=")[1];
        Matcher matcher = Pattern.compile("[0-9]+").matcher(in);
        if (matcher.find())
            return Integer.parseInt(matcher.group());
        return -1;
    }

    public int getJpgQuality() {
        return jpgQuality;
    }

    public void setJpgQuality(int jpgQuality) {
        this.jpgQuality = jpgQuality;
    }

    public boolean isBitmapSaved() {
        return bitmapSaved;
    }

    public SaveMode getSaveMode() {
        return saveMode;
    }

    public void setSaveMode(SaveMode mode) {
        if (!hasBLOB()) throw new UnsupportedOperationException("Unsupported BLOBs!");
        this.saveMode = mode;
        boolean hasUploadFunctions = hasUploadModes();
        if (mode == SaveMode.REMOTE_SAVE) {
            blobP.removeINDIPropertyListener(this);
            if (hasUploadFunctions) {
                try {
                    uploadClientE.setDesiredValue(Constants.SwitchStatus.OFF);
                    uploadLocalE.setDesiredValue(Constants.SwitchStatus.ON);
                    uploadBothE.setDesiredValue(Constants.SwitchStatus.OFF);
                } catch (Exception e) {
                    Log.e(TAG, e.getLocalizedMessage(), e);
                }
            }
        } else {
            blobP.addINDIPropertyListener(this);
        }
        if (hasUploadFunctions) {
            try {
                switch (saveMode) {
                    case REMOTE_SAVE:
                        uploadClientE.setDesiredValue(Constants.SwitchStatus.OFF);
                        uploadBothE.setDesiredValue(Constants.SwitchStatus.OFF);
                        uploadLocalE.setDesiredValue(Constants.SwitchStatus.ON);
                        break;
                    case REMOTE_SAVE_AND_SHOW:
                        uploadClientE.setDesiredValue(Constants.SwitchStatus.OFF);
                        uploadLocalE.setDesiredValue(Constants.SwitchStatus.OFF);
                        uploadBothE.setDesiredValue(Constants.SwitchStatus.ON);
                        break;
                    case SAVE_JPG_AND_SHOW:
                    case SHOW_ONLY:
                        uploadBothE.setDesiredValue(Constants.SwitchStatus.OFF);
                        uploadLocalE.setDesiredValue(Constants.SwitchStatus.OFF);
                        uploadClientE.setDesiredValue(Constants.SwitchStatus.ON);
                        break;
                }
                new PropUpdater(uploadModeP).start();
            } catch (Exception e) {
                Log.e(TAG, e.getLocalizedMessage(), e);
            }
        }
        new Thread(() -> {
            try {
                device.blobsEnable((mode == SaveMode.REMOTE_SAVE) ? Constants.BLOBEnables.NEVER : Constants.BLOBEnables.ALSO, blobP);
            } catch (Exception e) {
                Log.e(TAG, e.getLocalizedMessage(), e);
            }
        }, "INDICamera BLOB enabler").start();
    }

    public void stopReceiving() {
        new Thread(() -> {
            try {
                device.blobsEnable(Constants.BLOBEnables.NEVER);
            } catch (Exception e) {
                Log.e(TAG, e.getLocalizedMessage(), e);
            }
        }, "INDICamera BLOB enabler").start();
    }

    public Uri saveImage() throws IOException {
        if (lastBitmap == null) throw new IllegalStateException("No Bitmap in memory!");
        Uri uri = saveImage(lastBitmap);
        bitmapSaved = true;
        return uri;
    }

    @SuppressLint("SimpleDateFormat")
    @SuppressWarnings({"ResultOfMethodCallIgnored", "SameParameterValue"})
    public Uri saveImage(@NonNull Bitmap bitmap) throws IOException {
        String folderName = context.getString(R.string.app_name),
                fileName = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        OutputStream stream;
        Uri uri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues contentValues = new ContentValues();
            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + File.separator + folderName);
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues);
            stream = resolver.openOutputStream(uri);
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED) {
                    if (!storagePermissionRequested) {
                        synchronized (listeners) {
                            for (CameraListener listener : listeners) {
                                if (listener.onRequestStoragePermission())
                                    storagePermissionRequested = true;
                            }
                        }
                    }
                    return null;
                }
            }
            String directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).toString() +
                    File.separator + folderName;
            File dirFile = new File(directory);
            if (!dirFile.exists()) dirFile.mkdir();
            File file = new File(directory, fileName + ".jpg");
            uri = Uri.fromFile(file);
            stream = new FileOutputStream(file);
            MediaScannerConnection.scanFile(context, new String[]{dirFile.toString()}, null, null);
        }
        bitmap.compress(Bitmap.CompressFormat.JPEG, jpgQuality, stream);
        stream.flush();
        stream.close();
        return uri;
    }

    @Override
    public synchronized void propertyChanged(INDIProperty<?> indiProperty) {
        if (indiProperty == blobP) {
            if (listeners.isEmpty()) return;
            queuedValue = blobE.getValue();
            if ((loadingThread == null) || (!loadingThread.isAlive())) startProcessing();
        } else if (indiProperty == exposureP) {
            final Constants.PropertyStates state = indiProperty.getState();
            synchronized (listeners) {
                for (CameraListener listener : listeners) {
                    listener.onCameraStateChange(state);
                }
            }
        }
    }

    public boolean canCapture() {
        return (exposureP != null) && (exposureE != null);
    }

    public boolean canAbort() {
        return (abortP != null) && (abortE != null);
    }

    public boolean hasBinning() {
        return (binningP != null) && (binningXE != null) && (binningYE != null);
    }

    public boolean hasPresets() {
        return (exposurePresetsP != null) && (exposurePresetsE != null);
    }

    public boolean hasISO() {
        return (isoP != null) && (isoE != null);
    }

    public boolean hasBLOB() {
        return (blobP != null) && (blobE != null);
    }

    public boolean hasFrameTypes() {
        return (frameTypeP != null) && (frameTypesE != null);
    }

    public boolean hasUploadModes() {
        return (uploadModeP != null) && (uploadLocalE != null) && (uploadClientE != null) && (uploadBothE != null);
    }

    public boolean hasUploadSettings() {
        return (uploadSettingsP != null) && (uploadDirE != null) && (uploadPrefixE != null);
    }

    public void capture(String exposureOrPreset) throws INDIException {
        boolean canCapture = canCapture(), hasPresets = hasPresets();
        if (canCapture && (!hasPresets)) {
            capture(Double.parseDouble(exposureOrPreset));
        } else if (hasPresets && (!canCapture)) {
            INDISwitchElement e = stringToCameraPreset(exposureOrPreset);
            if (e == null) {
                throw new INDIException("Camera preset not found!");
            } else {
                capture(e);
            }
        } else if (canCapture && hasPresets) {
            INDISwitchElement e = stringToCameraPreset(exposureOrPreset);
            if (e == null) {
                capture(Double.parseDouble(exposureOrPreset));
            } else {
                capture(e);
            }
        } else {
            throw new UnsupportedOperationException("Unsupported capture!");
        }
    }

    private INDISwitchElement stringToCameraPreset(String preset) {
        for (INDISwitchElement e : availableExposurePresetsE) {
            if (e.getLabel().equals(preset)) return e;
        }
        throw null;
    }

    public void capture(double exposure) throws INDIValueException {
        if (!canCapture()) throw new UnsupportedOperationException("Unsupported capture!");
        exposureE.setDesiredValue(exposure);
        new PropUpdater(exposureP).start();
    }

    public void capture(INDISwitchElement preset) throws INDIValueException {
        if (!hasPresets()) throw new UnsupportedOperationException("Unsupported presets!");
        for (INDISwitchElement e : exposurePresetsE) {
            e.setDesiredValue((e == preset) ? Constants.SwitchStatus.ON : Constants.SwitchStatus.OFF);
        }
        new PropUpdater(exposurePresetsP).start();
    }

    public void abort() throws INDIValueException {
        if (!canAbort()) throw new UnsupportedOperationException("Unsupported abort!");
        abortE.setDesiredValue(Constants.SwitchStatus.ON);
        new PropUpdater(abortP).start();
    }

    public void setBinning(int binning) throws INDIValueException {
        if (!hasBinning()) throw new UnsupportedOperationException("Unsupported binning!");
        binningXE.setDesiredValue((double) binning);
        binningYE.setDesiredValue((double) binning);
        new PropUpdater(binningP).start();
    }

    public void setISO(INDISwitchElement iso) throws INDIValueException {
        if (!hasISO()) throw new UnsupportedOperationException("Unsupported ISO!");
        for (INDISwitchElement e : isoE) {
            e.setDesiredValue((e == iso) ? Constants.SwitchStatus.ON : Constants.SwitchStatus.OFF);
        }
        new PropUpdater(isoP).start();
    }

    public void setFrameType(INDISwitchElement frameType) throws INDIValueException {
        if (!hasFrameTypes()) throw new UnsupportedOperationException("Unsupported frame types!");
        for (INDISwitchElement e : frameTypesE) {
            e.setDesiredValue((e == frameType) ? Constants.SwitchStatus.ON : Constants.SwitchStatus.OFF);
        }
        new PropUpdater(frameTypeP).start();
    }

    public void setUploadSettings(String uploadDir, String uploadPrefix) throws INDIValueException {
        if (!hasUploadSettings())
            throw new UnsupportedOperationException("Unsupported upload settings!");
        uploadDirE.setDesiredValue(uploadDir);
        uploadPrefixE.setDesiredValue(uploadPrefix);
        new PropUpdater(uploadSettingsP).start();
    }

    @SuppressWarnings("SuspiciousToArrayCall")
    public synchronized void processNewProp(INDIProperty<?> property) {
        String name = property.getName(), devName = device.getName();
        Log.i(TAG, "New Property (" + name + ") added to camera " + devName
                + ", elements: " + Arrays.toString(property.getElementNames()));
        switch (name) {
            case "CCD_EXPOSURE":
                if ((property instanceof INDINumberProperty) &&
                        ((exposureE = (INDINumberElement) property.getElement(INDIStandardElement.CCD_EXPOSURE_VALUE)) != null)) {
                    exposureP = (INDINumberProperty) property;
                    exposureP.addINDIPropertyListener(this);
                }
                break;
            case "CCD1":
                if ((property instanceof INDIBLOBProperty) && ((blobE = (INDIBLOBElement) property.getElement("CCD1")) != null)) {
                    blobP = (INDIBLOBProperty) property;
                }
                break;
            case "CCD_EXPOSURE_PRESETS":
                if (property instanceof INDISwitchProperty) {
                    List<?> presets = property.getElementsAsList();
                    exposurePresetsE = presets.toArray(new INDISwitchElement[0]);
                    final Iterator<?> iterator = presets.listIterator();
                    while (iterator.hasNext()) {
                        String label = ((INDISwitchElement) iterator.next()).getLabel().toLowerCase();
                        if (label.equals("time") || label.equals("bulb")) iterator.remove();
                    }
                    availableExposurePresetsE = presets.toArray(new INDISwitchElement[0]);
                    exposurePresetsP = (INDISwitchProperty) property;
                }
                break;
            case "CCD_ISO":
                if (property instanceof INDISwitchProperty) {
                    isoE = property.getElementsAsList().toArray(new INDISwitchElement[0]);
                    isoP = (INDISwitchProperty) property;
                }
                break;
            case "CCD_ABORT_EXPOSURE":
                if ((property instanceof INDISwitchProperty) && ((abortE = (INDISwitchElement) property.getElement(INDIStandardElement.ABORT)) != null)) {
                    abortP = (INDISwitchProperty) property;
                    abortP.addINDIPropertyListener(this);//TODO light state for abort
                }
                break;
            case "CCD_BINNING":
                if (property instanceof INDINumberProperty &&
                        ((binningXE = (INDINumberElement) property.getElement(INDIStandardElement.HOR_BIN)) != null) &&
                        ((binningYE = (INDINumberElement) property.getElement(INDIStandardElement.VER_BIN)) != null)) {
                    binningP = (INDINumberProperty) property;
                }
                break;
            case "CCD_FRAME_TYPE":
                if (property instanceof INDISwitchProperty) {
                    frameTypesE = property.getElementsAsList().toArray(new INDISwitchElement[0]);
                    frameTypeP = (INDISwitchProperty) property;
                }
                break;
            case "UPLOAD_MODE":
                if ((property instanceof INDISwitchProperty) &&
                        ((uploadClientE = (INDISwitchElement) property.getElement("UPLOAD_CLIENT")) != null) &&
                        ((uploadLocalE = (INDISwitchElement) property.getElement("UPLOAD_LOCAL")) != null) &&
                        ((uploadBothE = (INDISwitchElement) property.getElement("UPLOAD_BOTH")) != null)) {
                    uploadModeP = (INDISwitchProperty) property;
                }
                break;
            case "UPLOAD_SETTINGS":
                if ((property instanceof INDITextProperty) &&
                        ((uploadDirE = (INDITextElement) property.getElement("UPLOAD_DIR")) != null) &&
                        ((uploadPrefixE = (INDITextElement) property.getElement("UPLOAD_PREFIX")) != null)) {
                    uploadSettingsP = (INDITextProperty) property;
                }
                break;
            default:
                return;
        }
        synchronized (listeners) {
            for (CameraListener listener : listeners) {
                listener.onCameraFunctionsChange();
            }
        }
    }

    public synchronized boolean removeProp(INDIProperty<?> property) {
        switch (property.getName()) {
            case "CCD_EXPOSURE":
                exposureP.removeINDIPropertyListener(this);
                exposureE = null;
                exposureP = null;
                break;
            case "CCD1":
                blobE = null;
                blobP = null;
                break;
            case "CCD_EXPOSURE_PRESETS":
                availableExposurePresetsE = null;
                exposurePresetsE = null;
                exposurePresetsP = null;
                break;
            case "CCD_ISO":
                isoE = null;
                isoP = null;
                break;
            case "CCD_BINNING":
                binningXE = binningYE = null;
                binningP = null;
                break;
            case "CCD_FRAME_TYPE":
                frameTypesE = null;
                frameTypeP = null;
                break;
            case "UPLOAD_MODE":
                uploadClientE = uploadLocalE = uploadBothE = null;
                uploadModeP = null;
                break;
            case "UPLOAD_SETTINGS":
                uploadDirE = uploadPrefixE = null;
                uploadSettingsP = null;
                break;
            default:
                return false;
        }
        synchronized (listeners) {
            for (CameraListener listener : listeners) {
                listener.onCameraFunctionsChange();
            }
        }
        return (blobP == null) && (exposureP == null) && (exposurePresetsP == null) &&
                (abortP == null) && (binningP == null) && (isoP == null);
    }

    public synchronized void terminate() {
        synchronized (listeners) {
            listeners.clear();
        }
        blobP = null;
        blobE = null;
        if (exposureP != null) {
            exposureP.removeINDIPropertyListener(this);
            exposureP = null;
        }
        exposureE = null;
        exposurePresetsP = null;
        exposurePresetsE = availableExposurePresetsE = null;
        abortP = null;
        abortE = null;
        binningP = null;
        binningXE = binningYE = null;
        isoP = null;
        isoE = null;
        uploadModeP = null;
        uploadLocalE = uploadClientE = uploadBothE = null;
        uploadSettingsP = null;
        uploadDirE = uploadPrefixE = null;
        frameTypeP = null;
        frameTypesE = null;
    }

    @NonNull
    @Override
    public String toString() {
        return device.getName();
    }

    public boolean hasBitmap() {
        return lastBitmap != null;
    }

    public Bitmap getLastBitmap() {
        return lastBitmap;
    }

    public void recycle() {
        handler.post(() -> {
            synchronized (listeners) {
                for (CameraListener listener : listeners) {
                    listener.onBitmapDestroy();
                }
            }
            if (lastBitmap != null) {
                lastBitmap.recycle();
                lastBitmap = null;
            }
        });
    }

    public synchronized void reloadBitmap() {
        if (!listeners.isEmpty() && (blobE != null)) {
            queuedValue = blobE.getValue();
            if ((loadingThread == null) || (!loadingThread.isAlive())) startProcessing();
        }
    }

    public void addListener(CameraListener listener) {
        synchronized (listeners) {
            this.listeners.add(listener);
        }
    }

    public void removeListener(CameraListener listener) {
        synchronized (listeners) {
            this.listeners.remove(listener);
        }
    }

    private void onException(final Throwable throwable) {
        handler.post(() -> {
            synchronized (listeners) {
                for (CameraListener listener : listeners) {
                    listener.onBitmapDestroy();
                    listener.onINDICameraError(throwable);
                }
            }
            if (lastBitmap != null) {
                lastBitmap.recycle();
                lastBitmap = null;
            }
        });
    }

    public void setStretch(boolean stretch) {
        this.stretch = stretch;
    }

    private synchronized void loadingFinished(Bitmap bitmap, String[] metadata) throws IOException {
        if ((saveMode == SaveMode.SAVE_JPG_AND_SHOW) && (bitmap != null)) {
            saveImage(bitmap);
            bitmapSaved = true;
        } else {
            bitmapSaved = false;
        }
        if (listeners.isEmpty()) {
            if (lastBitmap != null) {
                lastBitmap.recycle();
                lastBitmap = null;
            }
            if (bitmap != null) bitmap.recycle();
            return;
        }
        handler.post(() -> {
            synchronized (listeners) {
                for (CameraListener listener : listeners) {
                    listener.onImageLoaded(bitmap, metadata);
                }
            }
            if (lastBitmap != null) lastBitmap.recycle();
            lastBitmap = bitmap;
        });
        if (queuedValue != null) startProcessing();
    }

    private synchronized void startProcessing() {
        loadingThread = new LoadingThread(queuedValue);
        loadingThread.start();
        queuedValue = null;
        synchronized (listeners) {
            for (CameraListener listener : listeners) {
                handler.post(listener::onImageLoading);
            }
        }
    }

    public enum SaveMode {
        SHOW_ONLY(R.string.ccd_image_show_only),
        SAVE_JPG_AND_SHOW(R.string.ccd_image_save_show),
        REMOTE_SAVE(R.string.ccd_image_remote_save),
        REMOTE_SAVE_AND_SHOW(R.string.ccd_image_remote_and_display);

        private final int stringId;

        SaveMode(int stringId) {
            this.stringId = stringId;
        }

        public String toString(Context context) {
            return context.getString(stringId);
        }
    }

    public interface CameraListener {

        default void onCameraFunctionsChange() {
        }

        default boolean onRequestStoragePermission() {
            return false;
        }

        default void onImageLoading() {
        }

        void onImageLoaded(@Nullable Bitmap bitmap, String[] metadata);

        void onBitmapDestroy();

        default void onINDICameraError(Throwable e) {
        }

        default void onCameraStateChange(Constants.PropertyStates state) {
        }
    }

    private class LoadingThread extends Thread {

        private final INDIBLOBValue blobValue;

        private LoadingThread(@NonNull INDIBLOBValue blobValue) {
            super("INDICamera loading thread");
            this.blobValue = blobValue;
        }

        @SuppressLint("DefaultLocale")
        @Override
        public void run() {
            try {
                String format = blobValue.getFormat();
                int blobSize = blobValue.getSize();
                if (format.equals("") || (blobSize == 0))
                    throw new FileNotFoundException();
                String blobSizeString = String.format("%.2f MB", blobSize / 1000000.0);
                byte[] blobData = blobValue.getBlobData();
                if (format.equals(".fits") || format.equals(".fit") || format.equals(".fts")) {
                    try (InputStream stream = new ByteArrayInputStream(blobData)) {
                        int width = 0, height = 0;
                        byte bitPerPix = 0;
                        byte[] headerBuffer = new byte[80];
                        int extraByte = -1;
                        headerLoop:
                        while (stream.read(headerBuffer, 0, 80) != -1) {
                            String card = new String(headerBuffer);
                            if (card.contains("BITPIX")) {
                                bitPerPix = (byte) findFITSLineValue(card);
                            } else if (card.contains("NAXIS1")) {
                                width = findFITSLineValue(card);
                            } else if (card.contains("NAXIS2")) {
                                height = findFITSLineValue(card);
                            } else if (card.contains("NAXIS")) {
                                if (findFITSLineValue(card) != 2)
                                    throw new IndexOutOfBoundsException("Color FITS are not yet supported.");
                            } else if (card.startsWith("END ")) {
                                while (true) {
                                    extraByte = stream.read();
                                    if (((char) extraByte) != ' ') break headerLoop;
                                    if (stream.skip(79) != 79) throw new EOFException();
                                }
                            }
                        }
                        if ((bitPerPix == 0) || (width <= 0) || (height <= 0))
                            throw new IllegalStateException("Invalid FITS image");
                        if (bitPerPix == 32)
                            throw new UnsupportedOperationException("32 bit FITS are not yet supported.");
                        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                        if (stretch) {
                            int[][] img = new int[width][height];
                            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
                            if (bitPerPix == 8) {
                                for (int h = 0; h < height; h++) {
                                    for (int w = 0; w < width; w++) {
                                        int val;
                                        if (extraByte == -1) {
                                            val = stream.read();
                                        } else {
                                            val = extraByte;
                                            extraByte = -1;
                                        }
                                        img[w][h] = val;
                                        if (val > max) max = val;
                                        if (min > val) min = val;
                                    }
                                }
                            } else if (bitPerPix == 16) {
                                for (int h = 0; h < height; h++) {
                                    for (int w = 0; w < width; w++) {
                                        int val;
                                        if (extraByte == -1) {
                                            val = (stream.read() << 8) | stream.read();
                                        } else {
                                            val = (extraByte << 8) | stream.read();
                                            extraByte = -1;
                                        }
                                        img[w][h] = val;
                                        if (val > max) max = val;
                                        if (min > val) min = val;
                                    }
                                }
                            }
                            double logMin = Math.log10(min), multiplier = 255.0 / (Math.log10(max) - logMin);
                            for (int w = 0; w < width; w++) {
                                for (int h = 0; h < height; h++) {
                                    int interpolation = (int) ((Math.log10(img[w][h]) - logMin) * multiplier);
                                    bitmap.setPixel(w, h, Color.rgb(interpolation, interpolation, interpolation));
                                }
                            }
                        } else {
                            if (bitPerPix == 8) {
                                for (int h = 0; h < height; h++) {
                                    for (int w = 0; w < width; w++) {
                                        int val;
                                        if (extraByte == -1) {
                                            val = stream.read();
                                        } else {
                                            val = extraByte;
                                            extraByte = -1;
                                        }
                                        bitmap.setPixel(w, h, Color.rgb(val, val, val));
                                    }
                                }
                            } else if (bitPerPix == 16) {
                                for (int h = 0; h < height; h++) {
                                    for (int w = 0; w < width; w++) {
                                        int val;
                                        if (extraByte == -1) {
                                            val = (stream.read() << 8) | stream.read();
                                        } else {
                                            val = (extraByte << 8) | stream.read();
                                            extraByte = -1;
                                        }
                                        val /= 257;
                                        bitmap.setPixel(w, h, Color.rgb(val, val, val));
                                    }
                                }
                            }
                        }
                        loadingFinished(bitmap, new String[]{
                                blobSizeString, width + "x" + height, format, String.valueOf(bitPerPix)});
                    }
                } else {
                    Bitmap bitmap = BitmapFactory.decodeByteArray(blobData, 0, blobSize);
                    if (bitmap == null) {
                        loadingFinished(null, new String[]{blobSizeString, null, format, null});
                    } else {
                        loadingFinished(bitmap, new String[]{
                                blobSizeString, bitmap.getWidth() + "x" + bitmap.getHeight(), format,
                                (format.equals(".jpg") || format.equals(".jpeg")) ? "8" : null});
                    }
                }
            } catch (Throwable t) {
                onException(t);
            }
        }
    }
}