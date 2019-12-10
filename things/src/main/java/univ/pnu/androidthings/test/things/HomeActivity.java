package univ.pnu.androidthings.test.things;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.Handler;

import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.PeripheralManager;
import com.microsoft.azure.sdk.iot.device.DeviceClient;
import com.microsoft.azure.sdk.iot.device.DeviceTwin.DeviceMethodData;
import com.microsoft.azure.sdk.iot.device.IotHubClientProtocol;
import com.microsoft.azure.sdk.iot.device.IotHubConnectionStatusChangeCallback;
import com.microsoft.azure.sdk.iot.device.IotHubConnectionStatusChangeReason;
import com.microsoft.azure.sdk.iot.device.IotHubEventCallback;
import com.microsoft.azure.sdk.iot.device.IotHubMessageResult;
import com.microsoft.azure.sdk.iot.device.IotHubStatusCode;
import com.microsoft.azure.sdk.iot.device.Message;
import com.microsoft.azure.sdk.iot.device.transport.IotHubConnectionStatus;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

public class HomeActivity extends Activity {

    public static final int METHOD_THROWS = 403;
    private static final int INTERVAL_BETWEEN_BLINKS_MS = 1000;
    private static final int METHOD_SUCCESS = 200;
    private static final int METHOD_NOT_DEFINED = 404;
    private final String connString = BuildConfig.DeviceConnectionString;
    IotHubClientProtocol protocol = IotHubClientProtocol.MQTT;
    private Handler mBlinkHandler = new Handler();
    private Handler mSendHandler = new Handler();
    private Gpio mBlinkGpio;
    private boolean mLedState = false;
    private int count;
    private double cpuTemperature;
    private String msgStr;
    private Message sendMessage;
    private DeviceClient client;
    private int sendMessagesInterval = 5000;
    private Runnable mBlinkRunnable = new Runnable() {
        @Override
        public void run() {
            // Exit Runnable if the GPIO is already closed
            if (mBlinkGpio == null) {
                return;
            }
            try {
                // Toggle the GPIO state
                mLedState = !mLedState;
                mBlinkGpio.setValue(mLedState);

                // Reschedule the same runnable in {#INTERVAL_BETWEEN_BLINKS_MS} milliseconds
                mBlinkHandler.postDelayed(mBlinkRunnable, INTERVAL_BETWEEN_BLINKS_MS);
            } catch (IOException e) {
            }
        }
    };
    private Runnable mSendRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                while (!checkInternetConnection()) {
                }
                initClient();

                sendMessages();
                mSendHandler.postDelayed(mSendRunnable, sendMessagesInterval);
            } catch (Exception e) {
                System.out.println("Exception while opening IoTHub connection: " + e);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            mSendHandler.post(mSendRunnable);

            String pinName = "BCM26";
            mBlinkGpio = PeripheralManager.getInstance().openGpio(pinName);
            mBlinkGpio.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            mBlinkHandler.post(mBlinkRunnable);
        } catch (Exception e) {
            System.out.println("Exception while blink led or opening IoTHub connection: " + e);
        }
    }

    public boolean checkInternetConnection() {
        ConnectivityManager cm = (ConnectivityManager) getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);

        return cm.getActiveNetworkInfo() != null && cm.getActiveNetworkInfo().isConnected();
    }

    private void sendMessages() {
        cpuTemperature = getCpuTemp();
        msgStr = "\"temperature\":" + String.format("%.2f", cpuTemperature);
        try {
            sendMessage = new Message(msgStr);
            sendMessage.setProperty("temperatureAlert", cpuTemperature > 50 ? "true" : "false");
            sendMessage.setMessageId(java.util.UUID.randomUUID().toString());
            System.out.println("Message Sent: " + msgStr);
            EventCallback eventCallback = new EventCallback();
            client.sendEventAsync(sendMessage, eventCallback, count);
            count++;
        } catch (Exception e) {
            System.err.println("[pnu]Exception while sending event: " + e);
        }
    }

    public double getCpuTemp() {
        Process process;

        try {
            process = Runtime.getRuntime().exec("cat sys/class/thermal/thermal_zone0/temp");
            process.waitFor();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            String line = reader.readLine();
            double cpuTemp = Double.parseDouble(line) / 1000.0f;

            return cpuTemp;

        } catch (Exception e) {
            e.printStackTrace();
            return 0.0f;
        }
    }

    private void initClient() throws URISyntaxException, IOException {
        if (client == null) {
            client = new DeviceClient(connString, protocol);
            client.registerConnectionStatusChangeCallback(new IotHubConnectionStatusChangeCallbackLogger(), new Object());
        }

        try {
            client.open();
            MessageCallback callback = new MessageCallback();
            client.setMessageCallback(callback, null);
            client.subscribeToDeviceMethod(new SampleDeviceMethodCallback(), getApplicationContext(), new DeviceMethodStatusCallBack(), null);
        } catch (Exception e) {
            System.err.println("Exception while opening IoTHub connection: " + e);
            client.closeNow();
            System.out.println("Shutting down...");
        }
    }

    private int method_setSendMessagesInterval(Object methodData) throws UnsupportedEncodingException, JSONException {
        String payload = new String((byte[]) methodData, StandardCharsets.UTF_8).replace("\"", "");
        JSONObject obj = new JSONObject(payload);
        sendMessagesInterval = obj.getInt("sendInterval");
        return METHOD_SUCCESS;
    }

    private int method_default(Object data) {
        System.out.println("invoking default method for this device");
        // Insert device specific code here
        return METHOD_NOT_DEFINED;
    }

    class EventCallback implements IotHubEventCallback {
        public void execute(IotHubStatusCode status, Object context) {
            Integer i = context instanceof Integer ? (Integer) context : 0;
            System.out.println("IoT Hub responded to message " + i.toString()
                    + " with status " + status.name());
        }
    }

    class MessageCallback implements com.microsoft.azure.sdk.iot.device.MessageCallback {
        public IotHubMessageResult execute(Message msg, Object context) {
            System.out.println(
                    "Received message with content: " + new String(msg.getBytes(), Message.DEFAULT_IOTHUB_MESSAGE_CHARSET));
            return IotHubMessageResult.COMPLETE;
        }
    }

    protected class IotHubConnectionStatusChangeCallbackLogger implements IotHubConnectionStatusChangeCallback {
        @Override
        public void execute(IotHubConnectionStatus status, IotHubConnectionStatusChangeReason statusChangeReason, Throwable throwable, Object callbackContext) {
            System.out.println();
            System.out.println("CONNECTION STATUS UPDATE: " + status);
            System.out.println("CONNECTION STATUS REASON: " + statusChangeReason);
            System.out.println("CONNECTION STATUS THROWABLE: " + (throwable == null ? "null" : throwable.getMessage()));
            System.out.println();

            if (throwable != null) {
                throwable.printStackTrace();
            }

            if (status == IotHubConnectionStatus.DISCONNECTED) {
                //connection was lost, and is not being re-established. Look at provided exception for
                // how to resolve this issue. Cannot send messages until this issue is resolved, and you manually
                // re-open the device client
                try {
                    client.open();
                    MessageCallback callback = new MessageCallback();
                    client.setMessageCallback(callback, null);
                    client.subscribeToDeviceMethod(new SampleDeviceMethodCallback(), getApplicationContext(), new DeviceMethodStatusCallBack(), null);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else if (status == IotHubConnectionStatus.DISCONNECTED_RETRYING) {
                //connection was lost, but is being re-established. Can still send messages, but they won't
                // be sent until the connection is re-established
            } else if (status == IotHubConnectionStatus.CONNECTED) {
                //Connection was successfully re-established. Can send messages.
            }
        }
    }

    protected class DeviceMethodStatusCallBack implements IotHubEventCallback {
        public void execute(IotHubStatusCode status, Object context) {
            System.out.println("IoT Hub responded to device method operation with status " + status.name());
        }
    }

    protected class SampleDeviceMethodCallback implements com.microsoft.azure.sdk.iot.device.DeviceTwin.DeviceMethodCallback {
        @Override
        public DeviceMethodData call(String methodName, Object methodData, Object context) {
            DeviceMethodData deviceMethodData;
            try {
                switch (methodName) {
                    case "setSendMessagesInterval": {
                        int status = method_setSendMessagesInterval(methodData);
                        deviceMethodData = new DeviceMethodData(status, "executed " + methodName);
                        break;
                    }
                    default: {
                        int status = method_default(methodData);
                        deviceMethodData = new DeviceMethodData(status, "executed " + methodName);
                    }
                }
            } catch (Exception e) {
                int status = METHOD_THROWS;
                deviceMethodData = new DeviceMethodData(status, "Method Throws " + methodName);
            }
            return deviceMethodData;
        }
    }
}
