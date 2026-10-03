package io.edgeai.app.integration;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Owns a real isolated TLS/dynamic-security broker and private credentials. */
final class StreamBrokerFixture implements AutoCloseable {
    final Path root;final Process process;final int port;
    StreamBrokerFixture(){try{
        root=Files.createTempDirectory("edgeai-stream-http-");root.toFile().deleteOnExit();
        for(String name:List.of("device.key","runner.key")){byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);Files.writeString(root.resolve(name),HexFormat.of().formatHex(key));Files.setPosixFilePermissions(root.resolve(name),java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}
        process=new ProcessBuilder("python3","src/test/fixtures/stream_broker.py",root.toString()).redirectError(root.resolve("fixture-error.log").toFile()).start();
        var reader=process.inputReader();long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();while(!reader.ready() && process.isAlive() && System.nanoTime()<deadline)Thread.sleep(20);
        if(!reader.ready()){process.destroy();throw new IllegalStateException("Isolated stream broker unavailable");}port=Integer.parseInt(reader.readLine());
    }catch(Exception e){throw new IllegalStateException("Stream HTTP fixture startup failed; private details suppressed");}}
    String file(String name){return root.resolve(name).toString();}
    public void close()throws Exception {process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();assertThat(process.waitFor(5,TimeUnit.SECONDS)).isTrue();}
        try(var paths=Files.walk(root)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
}
