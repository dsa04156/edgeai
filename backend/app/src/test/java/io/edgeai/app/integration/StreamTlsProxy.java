package io.edgeai.app.integration;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Real TLS termination to a single owned loopback backend; no fabricated replies. */
final class StreamTlsProxy implements AutoCloseable {
    private final Process process;
    private final Path errors;
    final String origin;
    StreamTlsProxy(StreamBrokerFixture broker,String target){
        Process child=null;
        try{
            errors=Files.createFile(broker.root.resolve("tls-"+UUID.randomUUID()+".log"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            child=new ProcessBuilder("python3","-W","error::ResourceWarning","src/test/fixtures/stream_tls_proxy.py",broker.root.toString(),target)
                .redirectError(errors.toFile()).start();
            var reader=child.inputReader();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!reader.ready() && child.isAlive() && System.nanoTime()<end)Thread.sleep(10);
            if(!reader.ready())throw new IllegalStateException();
            origin="https://localhost:"+Integer.parseInt(reader.readLine());process=child;
        }catch(Exception e){if(child!=null)child.destroyForcibly();throw new IllegalStateException("Owned test TLS proxy unavailable; private details suppressed");}
    }
    public void close()throws Exception{
        process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();assertThat(process.waitFor(5,TimeUnit.SECONDS)).isTrue();}
        assertThat(Files.size(errors)).as("Owned TLS proxy has no private error output").isZero();
    }
}
