package io.edgeai.app.config;

import java.net.URI;
import java.nio.file.*;
import java.security.cert.CertificateFactory;
import java.util.Base64;

/** Public connection material only. Re-encode certificates; never return arbitrary CA file contents. */
public record StreamConnectionSettings(String host,int port,boolean tls,String caPem) {
    public static StreamConnectionSettings read(String url,Path ca){
        try{
            var endpoint=URI.create(url);boolean tls="ssl".equals(endpoint.getScheme());
            if(endpoint.getHost()==null || endpoint.getHost().length()>253 || endpoint.getPort()<1 || endpoint.getPort()>65535
                || endpoint.getUserInfo()!=null || !endpoint.getPath().isEmpty() || endpoint.getQuery()!=null || endpoint.getFragment()!=null
                || !(tls || "tcp".equals(endpoint.getScheme()) && "127.0.0.1".equals(endpoint.getHost())))throw new IllegalArgumentException();
            var pem=new StringBuilder();
            if(tls)try(var in=Files.newInputStream(ca)){
                byte[] bytes=in.readNBytes(131073);if(bytes.length>131072)throw new IllegalArgumentException();
                var certs=CertificateFactory.getInstance("X.509").generateCertificates(new java.io.ByteArrayInputStream(bytes));
                if(certs.isEmpty() || certs.size()>16)throw new IllegalArgumentException();
                for(var cert:certs)pem.append("-----BEGIN CERTIFICATE-----\n").append(Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(cert.getEncoded())).append("\n-----END CERTIFICATE-----\n");
                if(pem.length()>131072)throw new IllegalArgumentException();
            }
            return new StreamConnectionSettings(endpoint.getHost(),endpoint.getPort(),tls,pem.toString());
        }catch(Exception e){throw new IllegalArgumentException("Invalid stream client endpoint or CA configuration");}
    }
}
