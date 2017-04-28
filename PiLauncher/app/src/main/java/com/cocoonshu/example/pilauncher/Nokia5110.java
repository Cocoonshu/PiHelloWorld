package com.cocoonshu.example.pilauncher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.text.TextPaint;
import android.util.Log;

import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.PeripheralManagerService;
import com.google.android.things.pio.SpiDevice;

import java.io.IOException;
import java.util.Arrays;

/**
 * @Author Cocoonshu
 * @Date 2017-04-27
 */
public class Nokia5110 {
    private static final String TAG                  = "Nokia5110";
    private static final String WorkThreadName       = "Nokia5110_DriverThread";
    private static final int    BitPerWord           = 8;
    private static final int    SpiSpeed             = 4000000;
    private static final int    PIXEL_WIDTH          = 84;
    private static final int    PIXEL_HEIGHT         = 48;
    private static final int    COLOR_ONE            = 0xFFFFFFFF;
    private static final int    COLOR_ZERO           = 0x00000000;
    private static final int    COLOR_GARY_THRESHOLD = 0x80;
    private static final float  FONT_SIZE            = 12;

    private static final int    MSG_TEST_LCD         = 0;
    private static final int    MSG_SETUP_LCD        = 1;
    private static final int    MSG_CLEAR_SCREEN     = 2;
    private static final int    MSG_DRAW_TEXT        = 3;

    private int[]                    mPixels        = null;
    private byte[]                   mBuffer        = null;
    private Bitmap                   mDrawingCache  = null;
    private boolean                  mEnabled       = false;
    private HandlerThread            mDriverThread  = null;
    private Handler                  mDriverHandler = null;
    private PeripheralManagerService mPeripheral    = null;
    private SpiDevice                mSPI           = null;
    private Gpio                     mDC            = null;
    private Gpio                     mBL            = null;
    private Gpio                     mRST           = null;
    private Gpio                     mCS            = null;
    private String                   mBusName       = null;
    private String                   mDCName        = null;
    private String                   mCSName        = null;
    private String                   mRSTName       = null;
    private String                   mBLName        = null;

    public Nokia5110(String name, String dc, String cs, String rst, String bl) {
        mBusName = name;
        mDCName  = dc;
        mCSName  = cs;
        mRSTName = rst;
        mBLName  = bl;
    }

    private void handleDriverMessage(Message message) {
        switch (message.what) {
            case MSG_TEST_LCD:     { Log.i(TAG, "[handleDriverMessage] MSG_TEST_LCD");     testLCDInner(); testLCD(); mTestWord++;} break;
            case MSG_SETUP_LCD:    { Log.i(TAG, "[handleDriverMessage] MSG_SETUP_LCD");    lcdSetup(); } break;
            case MSG_CLEAR_SCREEN: { Log.i(TAG, "[handleDriverMessage] MSG_CLEAR_SCREEN"); clearScreenInner(); } break;
            case MSG_DRAW_TEXT:    { Log.i(TAG, "[handleDriverMessage] MSG_DRAW_TEXT");    drawTextInner(message.arg1, message.arg2, (CharSequence)message.obj); } break;
        }
    }

    private void busSetup() {
        try {
            if (mSPI == null) {
                return;
            }
            mSPI.setMode(SpiDevice.MODE3);   // Clock signal idles high, data is transferred on the trailing clock edge
            mSPI.setFrequency(SpiSpeed);     // 4MHz
            mSPI.setBitsPerWord(BitPerWord); // 8BPW
            mSPI.setBitJustification(false); // MSB first

            if (mDC == null) {
                return;
            }
            mDC.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            mDC.setActiveType(Gpio.ACTIVE_HIGH);
            mDC.setValue(false);

            if (mCS == null) {
                return;
            }
            mCS.setDirection(Gpio.DIRECTION_OUT_INITIALLY_HIGH);
            mCS.setActiveType(Gpio.ACTIVE_LOW);
            mCS.setValue(false);

            if (mRST == null) {
                return;
            }
            mRST.setDirection(Gpio.DIRECTION_OUT_INITIALLY_HIGH);
            mRST.setActiveType(Gpio.ACTIVE_LOW);
            mRST.setValue(false);

            if (mBL == null) {
                return;
            }
            mBL.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            mBL.setActiveType(Gpio.ACTIVE_HIGH);
            mBL.setValue(false);
        } catch (IOException exp) {
            mEnabled = false;
        }
    }

    private void lcdSetup() {
        try {
            if (!mEnabled) {
                return;
            }

            if (mRST != null) {
                mRST.setValue(true);
                synchronized (mRST) {
                    try {
                        mRST.wait(0, 100);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                mRST.setValue(false);
            }
            if (mCS != null) {
                mCS.setValue(true);
                synchronized (mCS) {
                    try {
                        mCS.wait(0, 100);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                mCS.setValue(false);
            }

            mCS.setValue(true);
            sendCMD((byte) 0x21);
            sendCMD((byte) 0xC8);
            sendCMD((byte) 0x06);
            sendCMD((byte) 0x13);
            sendCMD((byte) 0x20);
            clearScreen();
            sendCMD((byte) 0x0C);
            mCS.setValue(false);
        } catch (IOException exp) {
            mEnabled = false;
        }
    }

    public void enableLighting(boolean enabled) {
        try {
            if (!mEnabled) {
                return;
            }

            if (mBL != null) {
                Log.i(TAG, "[enableLighting] " + enabled);
                mBL.setValue(enabled);
            }
        } catch (IOException exp) {
            // Ignore
        }
    }

    public boolean isLightingEnabled() {
        try {
            return mBL != null ? mBL.getValue() : false;
        } catch (IOException exp) {
            return false;
        }
    }

    private void sendCMD(byte cmd) {
        try {
            if (!mEnabled) {
                return;
            }

            mBuffer[0] = cmd;
            mDC.setValue(false);
            mSPI.write(mBuffer, 1);
        } catch (IOException exp) {
            // Ignore
        }
    }

    private void sendData(byte data) {
        try {
            if (!mEnabled) {
                return;
            }

            mBuffer[0] = data;
            mDC.setValue(true);
            mSPI.write(mBuffer, 1);
        } catch (IOException exp) {
            // Ignore
        }
    }

    private void sendData(byte[] data) {
        try{
            if (!mEnabled) {
                return;
            }

            mDC.setValue(true);
            mSPI.write(data, data.length);
        } catch (IOException exp) {
            // Ignore
        }
    }

    private void sendData(byte[] data, int length) {
        try {
            if (!mEnabled) {
                return;
            }

            mDC.setValue(true);
            mSPI.write(data, length);
        } catch (IOException exp) {
            // Ignore
        }
    }

    private static int RGB2Gary(int r, int g, int b) {
        return (r + (g << 2) + b) >> 2;
    }

    private void clearScreenInner() {
        try {
            if (!mEnabled) {
                return;
            }

            mCS.setValue(true);
            sendCMD((byte) 0x0C);
            sendCMD((byte) 0x80);
            Arrays.fill(mBuffer, (byte) 0x00);
            sendData(mBuffer);
            mCS.setValue(false);
        } catch (IOException exp) {
            // Ignore
        }
    }

    private void flushBufferInner() {
        mDrawingCache.getPixels(mPixels, 0, PIXEL_WIDTH, 0, 0, PIXEL_WIDTH, PIXEL_HEIGHT);
        Arrays.fill(mBuffer, (byte) 0x00);
        for (int pixel = 0; pixel < PIXEL_WIDTH * PIXEL_HEIGHT; pixel++) {
            int word  = pixel / BitPerWord;
            int bit   = pixel % BitPerWord;
            int color = mPixels[pixel];
            int gary  = RGB2Gary(Color.red(color), Color.green(color), Color.blue(color));
            mBuffer[word] |= gary > COLOR_GARY_THRESHOLD ? (1 << bit) : 0;
        }
    }

    private void drawTextInner(int x, int y, CharSequence text) {
        if (text == null) {
            return;
        }

        try {
            Canvas    canvas = new Canvas(mDrawingCache);
            TextPaint paint  = new TextPaint();
            paint.setColor(COLOR_ONE);
            paint.setTextSize(FONT_SIZE);
            paint.setSubpixelText(true);
            canvas.drawText(text.toString(), x, y, paint);
            flushBufferInner();

            if (!mEnabled) {
                return;
            }
            mDC.setValue(true);
            sendData(mBuffer);
            mDC.setValue(false);
        } catch (IOException exp) {
            // Ignore
        }
    }

    private int mTestWord = 0;
    public void testLCDInner() {
        try {
            if (!mEnabled) {
                return;
            }

            mCS.setValue(true);
            sendCMD((byte) 0x0C);
            sendCMD((byte) 0x80);
            Arrays.fill(mBuffer, mTestWord % 2 == 0 ? (byte) 0x00 : (byte) 0xFF);
            sendData(mBuffer);
            mCS.setValue(false);
        } catch (IOException exp) {
            // Ignore
        }
    }

    /**
     * Public interfaces
     */
    public void open() {
        if (mPeripheral == null) {
            mPeripheral = new PeripheralManagerService();
        }

        try {
            close();
            mSPI = mBusName != null ? mPeripheral.openSpiDevice(mBusName) : null;
            mDC  = mDCName  != null ? mPeripheral.openGpio(mDCName)       : null;
            mCS  = mCSName  != null ? mPeripheral.openGpio(mCSName)       : null;
            mRST = mRSTName != null ? mPeripheral.openGpio(mRSTName)      : null;
            mBL  = mBLName  != null ? mPeripheral.openGpio(mBLName)       : null;

            if (mSPI != null && mDC != null) {
                mEnabled = true;
                mBuffer = new byte[PIXEL_WIDTH * PIXEL_HEIGHT / BitPerWord];
                mPixels = new int[PIXEL_WIDTH * PIXEL_HEIGHT];
                mDrawingCache = Bitmap.createBitmap(PIXEL_WIDTH, PIXEL_HEIGHT, Bitmap.Config.RGB_565);
                mDriverThread = new HandlerThread(WorkThreadName, Thread.NORM_PRIORITY);
                mDriverThread.start();
                mDriverHandler = new Handler(mDriverThread.getLooper()) {
                    @Override
                    public void handleMessage(Message msg) {
                        handleDriverMessage(msg);
                    }
                };
                busSetup();
                mDriverHandler.sendEmptyMessage(MSG_SETUP_LCD);
            }
        } catch (IOException exp) {
            mEnabled = false;
        }
    }

    public void close() {
        if (mDriverThread != null) {
            mDriverHandler = null;
            mDriverThread.quitSafely();
        }

        try {
            mEnabled = false;
            if (mSPI != null) {
                mSPI.close();
            }
            if (mDC != null) {
                mDC.close();
            }
        } catch (IOException exp) {
            mEnabled = false;
        }
    }

    public void clearScreen() {
        mDriverHandler.obtainMessage(MSG_CLEAR_SCREEN).sendToTarget();
    }

    public void drawText(int x, int y, CharSequence text) {
        mDriverHandler.obtainMessage(MSG_DRAW_TEXT, x, y, text).sendToTarget();
    }

    public void testLCD() {
        mDriverHandler.sendEmptyMessageDelayed(MSG_TEST_LCD, 1000);
    }
}
