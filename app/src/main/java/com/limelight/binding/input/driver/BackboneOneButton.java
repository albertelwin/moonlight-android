package com.limelight.binding.input.driver;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.os.SystemClock;

import com.limelight.LimeLog;
import com.limelight.nvstream.jni.MoonBridge;

import com.limelight.nvstream.input.ControllerPacket;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// USB Driver for Backbone One buttons (these are sent via a separate USB interface, not the standard HID)
// Maps both Backbone and screenshot buttons to Xbox Home/Guide button
public class BackboneOneButton extends AbstractController {
    public static final int VENDOR_ID = 0x358A; // Backbone Labs, Inc.

    protected final UsbDevice device;
    protected final UsbDeviceConnection connection;

    private Thread inputThread;
    private boolean stopped;

    protected UsbEndpoint inEndpt;

    private int backboneButtonState;
    private int screenshotButtonState;

    public BackboneOneButton(UsbDevice device, UsbDeviceConnection connection, int deviceId, UsbDriverListener listener) {
        super(deviceId, listener, device.getVendorId(), device.getProductId());
        this.device = device;
        this.connection = connection;
        this.type = MoonBridge.LI_CTYPE_XBOX;
        this.capabilities = 0;
        this.buttonFlags = 0;
        backboneButtonState = 0;
        screenshotButtonState = 0;
    }

    public static boolean isBackboneOne(int vendorId, int productId) {
        if ((vendorId == VENDOR_ID) && ((productId == 0x201) || (productId == 0x202))) // 0x201 = with headphones attached, 0x202 = without
            return true;
        return false;
    }

    public static boolean canClaimInterface(UsbDevice device) {
        return isBackboneOne(device.getVendorId(), device.getProductId());
    }

    private Thread createInputThread() {
        return new Thread() {
            public void run() {
                try {
                    // Delay for a moment before reporting the new gamepad and
                    // accepting new input. This allows time for the old InputDevice
                    // to go away before we reclaim its spot. If the old device is still
                    // around when we call notifyDeviceAdded(), we won't be able to claim
                    // the controller number used by the original InputDevice.
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }

                // Report that we're added _before_ reporting input
                notifyDeviceAdded();

                while (!isInterrupted() && !stopped) {
                    byte[] buffer = new byte[64];

                    int res;

                    //
                    // There's no way that I can tell to determine if a device has failed
                    // or if the timeout has simply expired. We'll check how long the transfer
                    // took to fail and assume the device failed if it happened before the timeout
                    // expired.
                    //

                    do {
                        // Read the next input state packet
                        long lastMillis = SystemClock.uptimeMillis();
                        res = connection.bulkTransfer(inEndpt, buffer, buffer.length, 3000);

                        // If we get a zero length response, treat it as an error
                        if (res == 0) {
                            res = -1;
                        }

                        if (res == -1 && SystemClock.uptimeMillis() - lastMillis < 1000) {
                            LimeLog.warning("Detected device I/O error");
                            BackboneOneButton.this.stop();
                            break;
                        }
                    } while (res == -1 && !isInterrupted() && !stopped);

                    if (res == -1 || stopped) {
                        break;
                    }

                    if (handleRead(ByteBuffer.wrap(buffer, 0, res).order(ByteOrder.LITTLE_ENDIAN))) {
                        // Report input if handleRead() returns true
                        reportInput();
                    }
                }
            }
        };
    }

    public boolean start() {
        // Find Backbone button interface
        UsbInterface iface = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface it = device.getInterface(i);
            if (it.getInterfaceClass() == 255 && it.getInterfaceSubclass() == 2 && it.getInterfaceProtocol() == 1) {
                iface = it;
                break;
            }
        }

        if (iface == null)
            return false;

        if (!connection.claimInterface(iface, true)) {
            LimeLog.warning("Failed to claim interfaces");
            return false;
        }

        for (int i = 0; i < iface.getEndpointCount(); i++) {
            UsbEndpoint endpt = iface.getEndpoint(i);
            if (endpt.getDirection() == UsbConstants.USB_DIR_IN) {
                if (inEndpt != null) {
                    LimeLog.warning("Found duplicate IN endpoint");
                    return false;
                }
                inEndpt = endpt;
            }
        }

        if (inEndpt == null) {
            LimeLog.warning("Missing required endpoint");
            return false;
        }

        // Start listening for controller input
        inputThread = createInputThread();
        inputThread.start();

        return true;
    }

    public void stop() {
        if (stopped) {
            return;
        }

        stopped = true;

        // Stop the input thread
        if (inputThread != null) {
            inputThread.interrupt();
            inputThread = null;
        }

        // Close the USB connection
        connection.close();

        // Report the device removed
        notifyDeviceRemoved();
    }

    private boolean handleRead(ByteBuffer buffer) {
        if (buffer.remaining() < 14) {
            LimeLog.severe("Read too small: " + buffer.remaining());
            return false;
        }

        // Backbone One specific input packet (this is probably not 100% correct)
        // buffer[7] seems to be key ID, buffer[12] is key state (up or down)
        buffer.position(buffer.position() + 7);
        byte keyId = buffer.get();
        buffer.position(buffer.position() + 4);
        byte isDown = buffer.get();

        int prevSpecialButtonState = backboneButtonState | screenshotButtonState;

        if (keyId == 22) {
            backboneButtonState = isDown != 0 ? 1 : 0;
        }
        else if (keyId == 23) {
            screenshotButtonState = isDown != 0 ? 1 : 0;
        }

        int curSpecialButtonState = backboneButtonState | screenshotButtonState;
        if (curSpecialButtonState != prevSpecialButtonState) {
            setButtonFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, curSpecialButtonState);
            return true;
        }

        return false;
    }

    @Override
    public void rumble(short lowFreqMotor, short highFreqMotor) {
    }

    @Override
    public void rumbleTriggers(short leftTrigger, short rightTrigger) {
    }
}
