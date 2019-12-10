package univ.pnu.androidthings.test;

import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.microsoft.azure.eventhubs.ConnectionStringBuilder;
import com.microsoft.azure.eventhubs.EventData;
import com.microsoft.azure.eventhubs.EventHubClient;
import com.microsoft.azure.eventhubs.EventHubException;
import com.microsoft.azure.eventhubs.EventHubRuntimeInformation;
import com.microsoft.azure.eventhubs.EventPosition;
import com.microsoft.azure.eventhubs.PartitionReceiver;
import com.microsoft.azure.sdk.iot.device.Message;
import com.microsoft.azure.sdk.iot.service.devicetwin.DeviceMethod;
import com.microsoft.azure.sdk.iot.service.devicetwin.MethodResult;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final String connString = BuildConfig.DeviceConnectionString;
    private static final String eventHubsCompatibleEndpoint = BuildConfig.HubEndpointString;
    private static final String eventHubsCompatiblePath = BuildConfig.HubPathString;
    private static final String iotHubSasKey = BuildConfig.SasKeyString;
    private static final String iotHubSasKeyName = BuildConfig.SasKeyNameString;

    private static ArrayList<PartitionReceiver> receivers = new ArrayList<>();
    private final Handler handler = new Handler();

    Button btnStart;
    Button btnStop;
    private static final Long responseTimeout = TimeUnit.SECONDS.toSeconds(200);

    TextView txtLastTempVal;
    TextView txtLastMsgReceivedVal;
    private static final Long connectTimeout = TimeUnit.SECONDS.toSeconds(5);
    LineChart lineChart;
    Button btnInvoke;
    final Runnable exceptionRunnable = new Runnable() {
        public void run() {
            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
            builder.setMessage(lastException);
            builder.show();
            System.out.println(lastException);
            btnStart.setEnabled(true);
            btnStop.setEnabled(false);
            btnInvoke.setEnabled(false);
        }
    };
    EditText txtLastInterval;

    ArrayList<Entry> entries = new ArrayList<>();
    ArrayList<String> labels = new ArrayList<>();
    DateTimeFormatter formatter;

    private String lastException;
    DeviceMethod methodClient;

    private ScheduledExecutorService executorService;
    private EventHubClient ehClient;
    private EventHubRuntimeInformation eventHubInfo;
    private int msgReceivedCount = 0;
    private Thread sendThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        btnInvoke = findViewById(R.id.btnInvoke);

        txtLastTempVal = findViewById(R.id.txtLastTempVal);
        txtLastMsgReceivedVal = findViewById(R.id.txtLastMsgReceivedVal);
        txtLastInterval = findViewById(R.id.txtLastInterval);

        lineChart = findViewById(R.id.chart);

        btnStop.setEnabled(false);
    }

    private void stop() {
        new Thread(() -> {
            try {
                System.out.println("Shutting down...");
                sendThread.interrupt();
                for (PartitionReceiver receiver : receivers) {
                    receiver.closeSync();
                }
                ehClient.closeSync();
                executorService.shutdown();
            } catch (Exception e) {
                lastException = "Exception while closing IoTHub connection: " + e;
                handler.post(exceptionRunnable);
            }
        }).start();
    }

    public void btnStopOnClick(View v) {
        stop();

        btnStart.setEnabled(true);
        btnInvoke.setEnabled(false);
        btnStop.setEnabled(false);
    }

    private void start() {
        formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
                .withLocale(Locale.KOREA)
                .withZone(ZoneId.systemDefault());

        sendThread = new Thread(() -> {
            try {
                initClient();
            } catch (Exception e) {
                lastException = "Exception while opening IoTHub connection: " + e;
                handler.post(exceptionRunnable);
            }
        });

        sendThread.start();
    }

    public void btnStartOnClick(View v) {
        start();

        btnStart.setEnabled(false);
        btnInvoke.setEnabled(true);
        btnStop.setEnabled(true);
    }

    private void invoke() {
        new Thread(new Runnable() {
            public void run() {
                MethodResult result;
                Map<String, Object> payload = new HashMap<String, Object>() {
                    {
                        put("sendInterval", txtLastInterval.getText().toString());
                    }
                };

                try {
                    methodClient = DeviceMethod.createFromConnectionString(connString);
                    result = methodClient.invoke("MyAndroidThingsDevice", "setSendMessagesInterval", responseTimeout, connectTimeout, payload);

                    if (result == null) {
                        throw new IOException("Method invoke returns null");
                    } else {
                        // Sending success
                    }
                } catch (Exception e) {
                    lastException = "Exception while trying to invoke direct method: " + e.toString();
                    handler.post(exceptionRunnable);
                }
            }
        }).start();
    }

    public void btnInvokeOnClick(View v) {
        invoke();

        btnStart.setEnabled(false);
        btnInvoke.setEnabled(true);
        btnStop.setEnabled(true);
    }

    private void initClient() throws Exception {
        final ConnectionStringBuilder connStr = new ConnectionStringBuilder()
                .setEndpoint(new URI(eventHubsCompatibleEndpoint))
                .setEventHubName(eventHubsCompatiblePath)
                .setSasKeyName(iotHubSasKeyName)
                .setSasKey(iotHubSasKey);

        executorService = Executors.newSingleThreadScheduledExecutor();
        ehClient = EventHubClient.createSync(connStr.toString(), executorService);
        eventHubInfo = ehClient.getRuntimeInformation().get();

        for (String partitionId : eventHubInfo.getPartitionIds()) {
            receiveMessages(ehClient, partitionId);
        }
    }

    private void receiveMessages(EventHubClient ehClient, String partitionId)
            throws Exception {

        final ExecutorService executorService = Executors.newSingleThreadExecutor();

        ehClient.createReceiver(EventHubClient.DEFAULT_CONSUMER_GROUP_NAME, partitionId,
                EventPosition.fromEnqueuedTime(Instant.now())).thenAcceptAsync(receiver -> {
            System.out.println(String.format("Starting receive loop on partition: %s", partitionId));
            System.out.println(String.format("Reading messages sent since: %s", Instant.now().toString()));

            receivers.add(receiver);

            while (true) {
                try {
                    Iterable<EventData> receivedEvents = receiver.receiveSync(100);

                    if (receivedEvents != null) {
                        for (EventData receivedEvent : receivedEvents) {

                            System.out.println(
                                    "Received message with content: " + new String(receivedEvent.getBytes(), Message.DEFAULT_IOTHUB_MESSAGE_CHARSET));

                            JSONObject obj = new JSONObject("{" + new String(receivedEvent.getBytes(), Message.DEFAULT_IOTHUB_MESSAGE_CHARSET) + "}");

                            System.out.println(obj);

                            double cpuTemperature = obj.getDouble("temperature");

                            entries.add(new Entry((float) cpuTemperature, msgReceivedCount));
                            labels.add(formatter.format(Instant.now()));

                            msgReceivedCount++;
                            TextView txtMsgsReceivedVal = findViewById(R.id.txtMsgsReceivedVal);
                            txtMsgsReceivedVal.setText(Integer.toString(msgReceivedCount));

                            txtLastTempVal.setText(String.valueOf(cpuTemperature));
                            txtLastMsgReceivedVal.setText("[" + new String(receivedEvent.getBytes(), Message.DEFAULT_IOTHUB_MESSAGE_CHARSET) + "]");

                            drawChart();
                        }
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                    System.out.println("JSON parsing error");
                } catch (EventHubException e) {
                    System.out.println("Error reading EventData");
                }
            }
        }, executorService);
    }

    private void drawChart() {
        new Thread(() -> {
            LineDataSet dataset = new LineDataSet(entries, "CPU Temperature");
            dataset.setLineWidth(5);
            dataset.setDrawValues(false);

            LineData data = new LineData(labels, dataset);
            lineChart.getAxisRight().setStartAtZero(false);
            lineChart.getAxisLeft().setStartAtZero(false);
            lineChart.setData(data);
            lineChart.invalidate();
        }).start();
    }
}
