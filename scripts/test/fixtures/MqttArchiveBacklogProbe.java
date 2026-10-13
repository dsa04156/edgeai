import io.edgeai.adapters.metrics.MqttObservationSubscriber;
import org.eclipse.paho.mqttv5.client.*;
import org.eclipse.paho.mqttv5.common.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Exercises the real archive subscriber on an owned, non-retained topic. */
public class MqttArchiveBacklogProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !Set.of("tcp://10.77.0.3:1883", "tcp://10.77.0.6:1883").contains(args[0]))
            throw new IllegalArgumentException("Explicit sensor broker required");
        String broker = args[0], id = UUID.randomUUID().toString().substring(0, 8);
        String topic = "edgeai/readiness/" + id;
        var received = ConcurrentHashMap.<Integer>newKeySet();
        var done = new CountDownLatch(40);
        var subscriber = new MqttObservationSubscriber("stage1-probe-" + id);
        var publisher = new MqttAsyncClient(broker, "stage1-publisher-" + id);
        try {
            subscriber.configure(Map.of(broker, Set.of(topic)), delivery -> {
                Thread.sleep(150);
                int value = Integer.parseInt(new String(delivery.payload(), StandardCharsets.UTF_8));
                if (received.add(value)) done.countDown();
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!subscriber.connected() && System.nanoTime() < deadline) Thread.sleep(50);
            if (!subscriber.connected()) throw new AssertionError("Subscriber readiness failed");
            var options = new MqttConnectionOptions(); options.setCleanStart(true);
            publisher.connect(options).waitForCompletion(3000);
            long started = System.nanoTime();
            for (int number = 1; number <= 40; number++) {
                var message = new MqttMessage(Integer.toString(number).getBytes(StandardCharsets.UTF_8));
                message.setQos(1); message.setRetained(false);
                publisher.publish(topic, message).waitForCompletion(3000);
            }
            boolean complete = done.await(15, TimeUnit.SECONDS);
            System.out.printf("{\"published\":40,\"received\":%d,\"elapsedSeconds\":%.2f,\"complete\":%s}%n",
                received.size(), (System.nanoTime() - started) / 1e9, complete);
            publisher.disconnect().waitForCompletion(3000);
            if (!complete) throw new AssertionError("Slow archive receiver exceeded bounded delivery deadline");
        } finally { subscriber.close(); publisher.close(); }
    }
}
